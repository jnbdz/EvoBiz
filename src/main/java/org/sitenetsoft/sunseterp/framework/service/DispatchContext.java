/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.sitenetsoft.sunseterp.framework.service;

import org.sitenetsoft.sunseterp.framework.base.component.ComponentConfig;
import org.sitenetsoft.sunseterp.framework.base.concurrent.ExecutionPool;
import org.sitenetsoft.sunseterp.framework.base.config.GenericConfigException;
import org.sitenetsoft.sunseterp.framework.base.config.MainResourceHandler;
import org.sitenetsoft.sunseterp.framework.base.config.ResourceHandler;
import org.sitenetsoft.sunseterp.framework.base.util.Debug;
import org.sitenetsoft.sunseterp.framework.base.util.cache.UtilCache;
import org.sitenetsoft.sunseterp.framework.entity.Delegator;
import org.sitenetsoft.sunseterp.framework.entity.GenericEntityConfException;
import org.sitenetsoft.sunseterp.framework.entity.config.model.DelegatorElement;
import org.sitenetsoft.sunseterp.framework.entity.config.model.EntityConfig;
import org.sitenetsoft.sunseterp.framework.security.Security;
import org.sitenetsoft.sunseterp.framework.service.config.ServiceConfigUtil;
import org.sitenetsoft.sunseterp.framework.service.config.model.GlobalServices;
import org.sitenetsoft.sunseterp.framework.service.eca.ServiceEcaUtil;
import org.w3c.dom.Document;

import javax.wsdl.WSDLException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Dispatcher Context
 */
@SuppressWarnings("serial")
public class DispatchContext implements Serializable {

    private static final String MODULE = DispatchContext.class.getName();

    private static final UtilCache<String, Map<String, ModelService>> MODEL_SERVICE_MAP_BY_MODEL =
            UtilCache.createUtilCache("service.ModelServiceMapByModel", 0, 0, false);

    // these four fields represent the immutable state of a DispatchContext object
    private final String name;
    private final transient ClassLoader loader;
    private final transient LocalDispatcher dispatcher;
    private final String model;

    /**
     * Creates new DispatchContext as an immutable object.
     * The "dispatcher" argument can be null if the "name" argument matches the name of a valid entity model reader.
     * The thread safety of a DispatchContext object is a consequence of its immutability.
     * @param name The immutable name of the DispatchContext
     * @param loader The immutable class loader
     * @param dispatcher The immutable dispatcher associated to the DispatchContext
     */
    public DispatchContext(String name, ClassLoader loader, LocalDispatcher dispatcher) {
        this.name = name;
        this.loader = loader;
        this.dispatcher = dispatcher;
        String modelName = null;
        if (this.dispatcher != null) {
            Delegator delegator = dispatcher.getDelegator();
            if (delegator != null) {
                DelegatorElement delegatorInfo = null;
                try {
                    delegatorInfo = EntityConfig.getInstance().getDelegator(delegator.getDelegatorBaseName());
                } catch (GenericEntityConfException e) {
                    Debug.logWarning(e, "Exception thrown while getting delegator config: ", MODULE);
                }
                if (delegatorInfo != null) {
                    modelName = delegatorInfo.getEntityModelReader();
                }
            }
        }
        if (modelName == null) {
            // if a modelName is not associated to the dispatcher (e.g. dispatcher is null) then use the name
            // of the DispatchContext as the model reader name
            modelName = name;
        }
        this.model = modelName;
        getGlobalServiceMap();
    }

    /**
     * Gets the classloader of this context
     * @return ClassLoader of the context
     */
    public ClassLoader getClassLoader() {
        return this.loader;
    }

    /**
     * Gets the name of the local dispatcher
     * @return String name of the LocalDispatcher object
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the LocalDispatcher used with this context
     * @return LocalDispatcher that was used to create this context
     */
    public LocalDispatcher getDispatcher() {
        return this.dispatcher;
    }

    /**
     * Gets the Delegator associated with this context/dispatcher
     * @return Delegator associated with this context
     */
    public Delegator getDelegator() {
        return dispatcher.getDelegator();
    }

    /**
     * Gets the Security object associated with this dispatcher
     * @return Security object associated with this dispatcher
     */
    public Security getSecurity() {
        return dispatcher.getSecurity();
    }

    // All the methods that follow are helper methods to retrieve service model information from cache (and manage the cache)
    // The cache object is static but most of these methods are not because the same service definition, is used with different
    // DispatchContext objects may result in different in/out attributes: this happens because the DispatchContext is associated to
    // a LocalDispatcher that is associated to a Delegator that is associated to a ModelReader; different ModelReaders could load the
    // same entity name from different files with different fields, and the service definition could automatically get the input/output
    // attributes from an entity.

    /**
     * Uses an existing map of name value pairs and extracts the keys which are used in serviceName
     * Note: This goes not guarantee the context will be 100% valid, there may be missing fields
     * @param serviceName The name of the service to obtain parameters for
     * @param mode The mode to use for building the new map (i.e. can be IN or OUT)
     * @param context The initial set of values to pull from
     * @return Map contains any valid values
     * @throws GenericServiceException
     */
    public Map<String, Object> makeValidContext(String serviceName, String mode, Map<String, ? extends Object> context)
            throws GenericServiceException {
        ModelService model = getModelService(serviceName);
        return makeValidContext(model, mode, context);
    }

    /**
     * Uses an existing map of name value pairs and extracts the keys which are used in serviceName
     * Note: This goes not guarantee the context will be 100% valid, there may be missing fields
     * @param model The ModelService object of the service to obtain parameters for
     * @param mode The mode to use for building the new map (i.e. can be IN or OUT)
     * @param context The initial set of values to pull from
     * @return Map contains any valid values
     * @throws GenericServiceException
     */
    public static Map<String, Object> makeValidContext(ModelService model, String mode, Map<String, ? extends Object> context)
            throws GenericServiceException {
        if (model == null || mode == null) {
            throw new GenericServiceException("Model service or mode is null! Should never happen.");
        }
        String upperCaseMode = mode.toUpperCase();
        if (!List.of(ModelService.IN_PARAM, ModelService.OUT_PARAM).contains(upperCaseMode)) {
            throw new GenericServiceException("Invalid mode, should be either IN or OUT");
        }
        return model.makeValid(context, upperCaseMode, true, null);
    }

    /**
     * Gets the ModelService instance that corresponds to given the name
     * @param serviceName Name of the service
     * @return GenericServiceModel that corresponds to the serviceName
     */
    public ModelService getModelService(String serviceName) throws GenericServiceException {
        Map<String, ModelService> serviceMap = getGlobalServiceMap();
        ModelService retVal = null;
        retVal = serviceMap.get(serviceName);
        if (retVal != null && !retVal.inheritedParameters()) {
            retVal.interfaceUpdate(this);
        }
        if (retVal == null) {
            throw new GenericServiceException("Cannot locate service by name (" + serviceName + ")");
        }
        return retVal;
    }

    /**
     * Gets all service names.
     * @return the all service names
     */
    public Set<String> getAllServiceNames() {
        Set<String> serviceNames = new TreeSet<>();

        Map<String, ModelService> globalServices = MODEL_SERVICE_MAP_BY_MODEL.get(this.model);
        if (globalServices != null) {
            serviceNames.addAll(globalServices.keySet());
        }
        return serviceNames;
    }

    /**
     * Gets wsdl.
     * @param serviceName the service name
     * @param locationURI the location uri
     * @return the wsdl
     * @throws GenericServiceException the generic service exception
     * @throws WSDLException the wsdl exception
     */
    public Document getWSDL(String serviceName, String locationURI) throws GenericServiceException, WSDLException {
        ModelService model = this.getModelService(serviceName);
        return model.toWSDL(locationURI);
    }

    private Callable<Map<String, ModelService>> createServiceReaderCallable(final ResourceHandler handler) {
        return () -> ModelServiceReader.getModelServiceMap(handler, DispatchContext.this.getDelegator());
    }

    private Map<String, ModelService> getGlobalServiceMap() {
        Map<String, ModelService> serviceMap = MODEL_SERVICE_MAP_BY_MODEL.get(this.model);
        if (serviceMap == null) {
            serviceMap = new HashMap<>();

            List<Future<Map<String, ModelService>>> futures = new LinkedList<>();
            List<GlobalServices> globalServicesList = null;
            try {
                globalServicesList = ServiceConfigUtil.getServiceEngine().getGlobalServices();
            } catch (GenericConfigException e) {
                // FIXME: Refactor API so exceptions can be thrown and caught.
                Debug.logError(e, MODULE);
                throw new RuntimeException(e.getMessage());
            }
            for (GlobalServices globalServices : globalServicesList) {
                ResourceHandler handler = new MainResourceHandler(ServiceConfigUtil.getServiceEngineXmlFileName(), globalServices.getLoader(),
                        globalServices.getLocation());
                futures.add(ExecutionPool.GLOBAL_FORK_JOIN.submit(createServiceReaderCallable(handler)));
            }

            // get all of the component resource model stuff, ie specified in each ofbiz-component.xml file
            for (ComponentConfig.ServiceResourceInfo componentResourceInfo: ComponentConfig.getAllServiceResourceInfos("model")) {
                futures.add(ExecutionPool.GLOBAL_FORK_JOIN.submit(createServiceReaderCallable(componentResourceInfo.createResourceHandler())));
            }
            for (Map<String, ModelService> servicesMap: ExecutionPool.getAllFutures(futures)) {
                if (servicesMap != null) {
                    serviceMap.putAll(servicesMap);
                }
            }

            Map<String, ModelService> cachedServiceMap = MODEL_SERVICE_MAP_BY_MODEL.putIfAbsentAndGet(this.model, serviceMap);
            if (cachedServiceMap == serviceMap) { // same object: this means that the object created by this thread was actually added to the cache
                ServiceEcaUtil.reloadConfig();
            }
        }
        return serviceMap;
    }
}