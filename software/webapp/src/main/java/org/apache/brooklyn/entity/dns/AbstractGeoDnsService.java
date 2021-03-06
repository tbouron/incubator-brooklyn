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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.entity.dns;

import java.util.Map;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.Group;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.location.geo.HostGeoInfo;
import org.apache.brooklyn.core.sensor.BasicAttributeSensor;

import com.google.common.reflect.TypeToken;

public interface AbstractGeoDnsService extends Entity {
    
    public static final ConfigKey<Boolean> INCLUDE_HOMELESS_ENTITIES = ConfigKeys.newBooleanConfigKey("geodns.includeHomeless", "Whether to include entities whose geo-coordinates cannot be inferred", false);
    public static final ConfigKey<Boolean> USE_HOSTNAMES = ConfigKeys.newBooleanConfigKey("geodns.useHostnames", "Whether to use the hostname for the returned value for routing, rather than IP address (defaults to true)", true);
    
    public static final AttributeSensor<Lifecycle> SERVICE_STATE_ACTUAL = Attributes.SERVICE_STATE_ACTUAL;
    public static final AttributeSensor<Boolean> SERVICE_UP = Startable.SERVICE_UP;
    public static final AttributeSensor<String> HOSTNAME = Attributes.HOSTNAME;
    public static final AttributeSensor<String> ADDRESS = Attributes.ADDRESS;
    @SuppressWarnings("serial")
    public static final AttributeSensor<Map<String,String>> TARGETS = new BasicAttributeSensor<Map<String,String>>(
            new TypeToken<Map<String,String>>() {}, "geodns.targets", "Map of targets currently being managed (entity ID to URL)");

    public void setServiceState(Lifecycle state);
    
    /** sets target to be a group whose *members* will be searched (non-Group items not supported) */
    // prior to 0.7.0 the API accepted non-group items, but did not handle them correctly
    public void setTargetEntityProvider(final Group entityProvider);
    
    /** should return the hostname which this DNS service is configuring */
    public String getHostname();
    
    public Map<Entity, HostGeoInfo> getTargetHosts();
}
