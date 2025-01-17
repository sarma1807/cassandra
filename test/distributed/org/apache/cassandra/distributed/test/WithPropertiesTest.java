/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.distributed.test;

import org.junit.Assert;
import org.junit.Test;

import org.apache.cassandra.config.CassandraRelevantProperties;
import org.apache.cassandra.distributed.shared.WithProperties;

public class WithPropertiesTest
{
    @Test
    public void testPreserveBeforeSet()
    {
        CassandraRelevantProperties booleanProperty = CassandraRelevantProperties.COM_SUN_MANAGEMENT_JMXREMOTE_SSL;
        boolean defaultPropertyValue = Boolean.parseBoolean(booleanProperty.getDefaultValue());
        boolean newPropertyValue = true;

        try (WithProperties properties = new WithProperties()
                                         .preserve(booleanProperty)
                                         .set(booleanProperty, newPropertyValue))
        {
            Assert.assertEquals("Property value must match", newPropertyValue, booleanProperty.getBoolean());
        }
        Assert.assertEquals("Property value must revert to the default value", defaultPropertyValue, booleanProperty.getBoolean());
    }
}
