#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# Wrapper script for running a split or regexp of a pytest run from cassandra-dtest
#

################################
#
# Prep
#
################################

[ $DEBUG ] && set -x

# help
if [ "$#" -lt 1 ] || [ "$1" == "-h" ]; then
    echo ""
    echo "Usage: $0 [-a|-t|-c|-j|-h]"
    echo "   -a Test target type: dtest, dtest-latest, ..."
    echo "   -t Test name regexp to run."
    echo "   -c Chunk to run in the form X/Y: Run chunk X from a total of Y chunks."
    echo ""
    echo "        default split_chunk is 1/1"
    exit 1
fi

# Pass in target to run, defaults to dtest
DTEST_TARGET="dtest"

# TODO implement repeated runs, eg CASSANDRA-18942
while getopts "a:t:c:hj:" opt; do
  case $opt in
    a ) DTEST_TARGET="$OPTARG"
        ;;
    t ) DTEST_SPLIT_CHUNK="$OPTARG"
        ;;
    c ) DTEST_SPLIT_CHUNK="$OPTARG"
        ;;
    h ) print_help
        exit 0
        ;;
    j ) ;; # To avoid failing on java_version param from docker/run_tests.sh
    \?) error 1 "Invalid option: -$OPTARG"
        ;;
  esac
done
shift $((OPTIND-1))
if [ "$#" -ne 0 ]; then
  error 1 "Unexpected arguments"
fi

# variables, with defaults
[ "x${CASSANDRA_DIR}" != "x" ] || CASSANDRA_DIR="$(readlink -f $(dirname "$0")/..)"
[ "x${CASSANDRA_DTEST_DIR}" != "x" ] || CASSANDRA_DTEST_DIR="$(readlink -f ${CASSANDRA_DIR}/../cassandra-dtest)"
[ "x${DIST_DIR}" != "x" ] || DIST_DIR="${CASSANDRA_DIR}/build"
[ "x${TMPDIR}" != "x" ] || { TMPDIR_SET=1 && export TMPDIR="$(mktemp -d ${DIST_DIR}/run-python-dtest.XXXXXX)" ; }
[ "x${CCM_CONFIG_DIR}" != "x" ] && ls $CCM_CONFIG_DIR

export PYTHONIOENCODING="utf-8"
export PYTHONUNBUFFERED=true
export CASS_DRIVER_NO_EXTENSIONS=true
export CASS_DRIVER_NO_CYTHON=true
export CCM_MAX_HEAP_SIZE="1024M"
export CCM_HEAP_NEWSIZE="512M"
export NUM_TOKENS="16"
# Have Cassandra skip all fsyncs to improve test performance and reliability
export CASSANDRA_SKIP_SYNC=true
unset CASSANDRA_HOME

# pre-conditions
command -v ant >/dev/null 2>&1 || { echo >&2 "ant needs to be installed"; exit 1; }
command -v virtualenv >/dev/null 2>&1 || { echo >&2 "virtualenv needs to be installed"; exit 1; }
[ -f "${CASSANDRA_DIR}/build.xml" ] || { echo >&2 "${CASSANDRA_DIR}/build.xml must exist"; exit 1; }
[ -d "${DIST_DIR}" ] || { mkdir -p "${DIST_DIR}" ; }
ALLOWED_DTEST_VARIANTS="novnode|large|latest|upgrade"
[[ "${DTEST_TARGET}" =~ ^dtest(-(${ALLOWED_DTEST_VARIANTS}))*$ ]] || { echo >&2 "Unknown dtest target: ${DTEST_TARGET}. Allowed variants are ${ALLOWED_DTEST_VARIANTS}"; exit 1; }

java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | awk -F. '{print $1}')
version=$(grep 'property\s*name=\"base.version\"' ${CASSANDRA_DIR}/build.xml |sed -ne 's/.*value=\"\([^"]*\)\".*/\1/p')
java_version_default=`grep 'property\s*name="java.default"' ${CASSANDRA_DIR}/build.xml |sed -ne 's/.*value="\([^"]*\)".*/\1/p'`

if [ "${java_version}" -eq 17 ] && [[ "${target}" == "dtest-upgrade" ]] ; then
    echo "Invalid JDK${java_version}. Only overlapping supported JDKs can be used when upgrading, as the same jdk must be used over the upgrade path."
    exit 1
fi

python_version=$(python -V 2>&1 | awk '{print $2}' | awk -F'.' '{print $1"."$2}')
python_regx_supported_versions="^(3.8|3.9|3.10|3.11)$"
[[ $python_version =~ $python_regx_supported_versions ]] || { echo "Python ${python_version} not supported."; exit 1; }

# check project is already built. no cleaning is done, so jenkins unstash works, beware.
[[ -f "${DIST_DIR}/apache-cassandra-${version}.jar" ]] || [[ -f "${DIST_DIR}/apache-cassandra-${version}-SNAPSHOT.jar" ]] || { echo "Project must be built first. Use \`ant jar\`. Build directory is ${DIST_DIR} with: $(ls ${DIST_DIR})"; exit 1; }

# check if dist artifacts exist, this breaks the dtests
[[ -d "${DIST_DIR}/dist" ]] && { echo "dtests don't work when build/dist ("${DIST_DIR}/dist") exists (from \`ant artifacts\`)"; exit 1; }

# print debug information on versions
java -version
ant -version
python --version
virtualenv --version

# cheap trick to ensure dependency libraries are in place. allows us to stash only project specific build artifacts.
ant -quiet -silent resolver-dist-lib

# Set up venv with dtest dependencies
set -e # enable immediate exit if venv setup fails

# fresh virtualenv and test logs results everytime
[[ "/" == "${DIST_DIR}" ]] || rm -rf "${DIST_DIR}/venv" "${DIST_DIR}/test/{html,output,logs}"

# re-use when possible the pre-installed virtualenv found in the cassandra-ubuntu2004_test docker image
virtualenv-clone ${BUILD_HOME}/env${python_version} ${DIST_DIR}/venv || virtualenv --python=python${python_version} ${DIST_DIR}/venv
source ${DIST_DIR}/venv/bin/activate
pip3 install --exists-action w -r ${CASSANDRA_DTEST_DIR}/requirements.txt
pip3 freeze

################################
#
# Main
#
################################

cd ${CASSANDRA_DTEST_DIR}

set +e # disable immediate exit from this point
DTEST_ARGS="--keep-failed-test-dir"
# Check for specific keywords in DTEST_TARGET and append corresponding options
if [[ "${DTEST_TARGET}" == *"-large"* ]]; then
    DTEST_ARGS+=" --only-resource-intensive-tests --force-resource-intensive-tests"
else
    DTEST_ARGS+=" --skip-resource-intensive-tests"
fi
if [[ "${DTEST_TARGET}" != *"-novnode"* ]]; then
    DTEST_ARGS+=" --use-vnodes --num-tokens=${NUM_TOKENS}"
fi
if [[ "${DTEST_TARGET}" == *"-latest"* ]]; then
    DTEST_ARGS+=" --configuration-yaml=cassandra_latest.yaml"
fi
if [[ "${DTEST_TARGET}" == *"-upgrade"* ]]; then
    DTEST_ARGS+=" --execute-upgrade-tests --execute-upgrade-tests-only --upgrade-target-version-only --upgrade-version-selection all"
fi

touch ${DIST_DIR}/test_list.txt
./run_dtests.py --cassandra-dir=${CASSANDRA_DIR} ${DTEST_ARGS} --dtest-print-tests-only --dtest-print-tests-output=${DIST_DIR}/test_list.txt 2>&1 > ${DIST_DIR}/test_stdout.txt

[[ $? -eq 0 ]] || { cat ${DIST_DIR}/test_stdout.txt ; exit 1; }

if [[ "${DTEST_SPLIT_CHUNK}" =~ ^[0-9]+/[0-9]+$ ]]; then
    split_cmd=split
    ( split --help 2>&1 ) | grep -q "r/K/N" || split_cmd=gsplit
    command -v ${split_cmd} >/dev/null 2>&1 || { echo >&2 "${split_cmd} needs to be installed"; exit 1; }
    SPLIT_TESTS=$(${split_cmd} -n r/${DTEST_SPLIT_CHUNK} ${DIST_DIR}/test_list.txt)
    if [[ -z "${SPLIT_TESTS}" ]]; then
      # something has to run in the split to generate a nosetest xml result (and to not rerun all tests)
      echo "Hacking ${DTEST_TARGET} to run only first test found as no tests in split ${DTEST_SPLIT_CHUNK} were found: "
      SPLIT_TESTS="$( echo ${DIST_DIR}/test_list.txt | head -n1)"
      echo "  ${SPLIT_TESTS}"
    fi
    SPLIT_STRING="_${DTEST_SPLIT_CHUNK//\//_}"
elif [[ "x" != "x${DTEST_SPLIT_CHUNK}" ]] ; then
    SPLIT_TESTS=$(grep -e "${DTEST_SPLIT_CHUNK}" ${DIST_DIR}/test_list.txt)
    [[ "x" != "x${SPLIT_TESTS}" ]] || { echo "no tests match regexp \"${DTEST_SPLIT_CHUNK}\""; exit 1; }
else
    SPLIT_TESTS=$(cat ${DIST_DIR}/test_list.txt)
fi
SPLIT_TESTS="${SPLIT_TESTS//$'\n'/ }"

pytest_results_file="${DIST_DIR}/test/output/nosetests.xml"
pytest_opts="-vv --log-cli-level=DEBUG --junit-xml=${pytest_results_file} --junit-prefix=${DTEST_TARGET} -s"

echo ""
echo "pytest ${pytest_opts} --cassandra-dir=${CASSANDRA_DIR} --keep-failed-test-dir ${DTEST_ARGS} ${SPLIT_TESTS}" 
echo ""

pytest ${pytest_opts}  --cassandra-dir=${CASSANDRA_DIR} --keep-failed-test-dir ${DTEST_ARGS} ${SPLIT_TESTS} 2>&1 | tee -a ${DIST_DIR}/test_stdout.txt

# tar up any ccm logs for easy retrieval
if ls ${TMPDIR}/*/test/*/logs/* &>/dev/null ; then
    mkdir -p ${DIST_DIR}/test/logs
    tar -C ${TMPDIR} -cJf ${DIST_DIR}/test/logs/ccm_logs.tar.xz ${TMPDIR}/*/test/*/logs
fi

# merge all unit xml files into one, and print summary test numbers
pushd ${CASSANDRA_DIR}/ >/dev/null
# remove <testsuites> wrapping elements. ant generate-test-report` doesn't like it, and update testsuite name
sed -r "s/<[\/]?testsuites>//g" ${pytest_results_file} > ${TMPDIR}/nosetests.xml
cat ${TMPDIR}/nosetests.xml > ${pytest_results_file}
sed "s/testsuite name=\"Cassandra dtests\"/testsuite name=\"${DTEST_TARGET}_jdk${java_version}_python${python_version}_cython${cython}_$(uname -m)${SPLIT_STRING}\"/g" ${pytest_results_file} > ${TMPDIR}/nosetests.xml
cat ${TMPDIR}/nosetests.xml > ${pytest_results_file}

ant -quiet -silent generate-test-report
popd  >/dev/null

################################
#
# Clean
#
################################

if [ ${TMPDIR_SET} ] ; then
    [[ "${TMPDIR}" == *"${DIST_DIR}/run-python-dtest."* ]] && rm -rf "${TMPDIR}"
    unset TMPDIR
fi
deactivate

# Exit cleanly for usable "Unstable" status
exit 0
