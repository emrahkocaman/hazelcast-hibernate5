/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.hibernate.serialization;

import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.annotation.QuickTest;
import com.hazelcast.util.FilteringClassLoader;
import org.hibernate.cache.spi.entry.StandardCacheEntryImpl;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import static org.junit.Assert.assertFalse;

@RunWith(HazelcastSerialClassRunner.class)
@Category(QuickTest.class)
public class HibernateSerializationHookNonAvailableTest {

    private static final Field ORIGINAL;
    private static final Field TYPE_MAP;
    private static final Method GET_SERIALIZATION_SERVICE;

    private static final ClassLoader FILTERING_CLASS_LOADER;

    static {
        try {
            List<String> excludes = Collections.singletonList("org.hibernate");
            FILTERING_CLASS_LOADER = new FilteringClassLoader(excludes, "com.hazelcast");

            String hazelcastInstanceImplClassName = "com.hazelcast.instance.HazelcastInstanceImpl";
            Class<?> hazelcastInstanceImplClass = FILTERING_CLASS_LOADER.loadClass(hazelcastInstanceImplClassName);
            GET_SERIALIZATION_SERVICE = hazelcastInstanceImplClass.getMethod("getSerializationService");

            String hazelcastInstanceProxyClassName = "com.hazelcast.instance.HazelcastInstanceProxy";
            Class<?> hazelcastInstanceProxyClass = FILTERING_CLASS_LOADER.loadClass(hazelcastInstanceProxyClassName);
            ORIGINAL = hazelcastInstanceProxyClass.getDeclaredField("original");
            ORIGINAL.setAccessible(true);

            String serializationServiceImplClassName = "com.hazelcast.internal.serialization.impl.AbstractSerializationService";
            Class<?> serializationServiceImplClass = FILTERING_CLASS_LOADER.loadClass(serializationServiceImplClassName);
            TYPE_MAP = serializationServiceImplClass.getDeclaredField("typeMap");
            TYPE_MAP.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testAutoregistrationOnHibernate5NonAvailable()
            throws Exception {

        Thread thread = Thread.currentThread();
        ClassLoader tccl = thread.getContextClassLoader();

        try {
            thread.setContextClassLoader(FILTERING_CLASS_LOADER);

            Class<?> configClazz = FILTERING_CLASS_LOADER.loadClass("com.hazelcast.config.Config");
            Object config = configClazz.newInstance();
            Method setClassLoader = configClazz.getDeclaredMethod("setClassLoader", ClassLoader.class);

            setClassLoader.invoke(config, FILTERING_CLASS_LOADER);

            Class<?> hazelcastClazz = FILTERING_CLASS_LOADER.loadClass("com.hazelcast.core.Hazelcast");
            Method newHazelcastInstance = hazelcastClazz.getDeclaredMethod("newHazelcastInstance", configClazz);

            Object hz = newHazelcastInstance.invoke(hazelcastClazz, config);
            Object impl = ORIGINAL.get(hz);
            Object serializationService = GET_SERIALIZATION_SERVICE.invoke(impl);
            ConcurrentMap<Class, ?> typeMap = (ConcurrentMap<Class, ?>) TYPE_MAP.get(serializationService);
            boolean cacheKeySerializerFound = false;
            boolean cacheEntrySerializerFound = false;
            boolean naturalIdKeySerializerFound = false;
            for (Class clazz : typeMap.keySet()) {
                // The Old* implementations are matched by class name here just to avoid the Reflection hassle
                // of retrieving their classes, since they're package-private
                if ("org.hibernate.cache.internal.OldCacheKeyImplementation".equals(clazz.getName())) {
                    cacheKeySerializerFound = true;
                } else if ("org.hibernate.cache.internal.OldNaturalIdCacheKey".equals(clazz.getName())) {
                    naturalIdKeySerializerFound = true;
                } else if (StandardCacheEntryImpl.class.equals(clazz)) {
                    cacheEntrySerializerFound = true;
                }
            }

            assertFalse("CacheKey serializer found", cacheKeySerializerFound);
            assertFalse("CacheEntry serializer found", cacheEntrySerializerFound);
            assertFalse("NaturalIdCacheKey serializer found", naturalIdKeySerializerFound);
        } finally {
            thread.setContextClassLoader(tccl);
        }
    }
}
