/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.handler.admin;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.solr.api.AnnotatedApi;
import org.apache.solr.api.Command;
import org.apache.solr.api.ContainerPluginsRegistry;
import org.apache.solr.api.EndPoint;
import org.apache.solr.api.PayloadObj;
import org.apache.solr.client.solrj.SolrRequest.METHOD;
import org.apache.solr.client.solrj.request.beans.PluginMeta;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.PermissionNameProvider;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.lucene.util.IOUtils.closeWhileHandlingException;

/**
 * API to maintain container-level plugin configurations.
 */
public class ContainerPluginsApi {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String PLUGIN = ZkStateReader.CONTAINER_PLUGINS;
  private final Supplier<SolrZkClient> zkClientSupplier;
  private final CoreContainer coreContainer;
  public final Read readAPI = new Read();
  public final Edit editAPI = new Edit();

  public ContainerPluginsApi(CoreContainer coreContainer) {
    this.zkClientSupplier = coreContainer.zkClientSupplier;
    this.coreContainer = coreContainer;
  }

  /**
   * API for reading the current plugin configurations.
   */
  public class Read {
    @EndPoint(method = METHOD.GET,
        path = "/cluster/plugin",
        permission = PermissionNameProvider.Name.COLL_READ_PERM)
    public void list(SolrQueryRequest req, SolrQueryResponse rsp) throws IOException {
      rsp.add(PLUGIN, plugins(zkClientSupplier));
    }
  }

  /**
   * API for editing the plugin configurations.
   */
  @EndPoint(method = METHOD.POST,
      path = "/cluster/plugin",
      permission = PermissionNameProvider.Name.COLL_EDIT_PERM)
  public class Edit {

    @Command(name = "add")
    public void add(PayloadObj<PluginMeta> payload) throws IOException {
      PluginMeta info = payload.get();
      validateConfig(payload, info);
      if(payload.hasError()) return;
      persistPlugins(map -> {
        if (map.containsKey(info.name)) {
          payload.addError(info.name + " already exists");
          return null;
        }
        map.put(info.name, payload.getDataMap());
        return map;
      });
    }

    @Command(name = "remove")
    public void remove(PayloadObj<String> payload) throws IOException {
      persistPlugins(map -> {
        if (map.remove(payload.get()) == null) {
          payload.addError("No such plugin: " + payload.get());
          return null;
        }
        return map;
      });
    }

    @Command(name = "update")
    @SuppressWarnings("unchecked")
    public void update(PayloadObj<PluginMeta> payload) throws IOException {
      PluginMeta info = payload.get();
      validateConfig(payload, info);
      if(payload.hasError()) return;
      persistPlugins(map -> {
        Map<String, Object> existing = (Map<String, Object>) map.get(info.name);
        if (existing == null) {
          payload.addError("No such plugin: " + info.name);
          return null;
        } else {
          Map<String, Object> jsonObj = payload.getDataMap();
          if(Objects.equals(jsonObj, existing)) {
            //no need to change anything
            return null;
          }
          map.put(info.name, jsonObj);
          return map;
        }
      });
    }
  }

  private void validateConfig(PayloadObj<PluginMeta> payload, PluginMeta info) throws IOException {
    if (info.klass.indexOf(':') > 0) {
      if (info.version == null) {
        payload.addError("Using package. must provide a packageVersion");
        return;
      }
    }
    List<String> errs = new ArrayList<>();
    ContainerPluginsRegistry.ApiInfo apiInfo = coreContainer.getContainerPluginsRegistry().createInfo( payload.getDataMap(),  errs);
    if (!errs.isEmpty()) {
      for (String err : errs) payload.addError(err);
      return;
    }
    AnnotatedApi api = null ;
    try {
      apiInfo.init();
    } catch (Exception e) {
      log.error("Error instantiating plugin ", e);
      errs.add(e.getMessage());
      return;
    } finally {
      closeWhileHandlingException(api);
    }
  }

  /**
   * Retrieve the current plugin configurations.
   * @param zkClientSupplier supplier of {@link SolrZkClient}
   * @return current plugin configurations, where keys are plugin names and values
   * are {@link PluginMeta} plugin metadata.
   * @throws IOException on IO errors
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> plugins(Supplier<SolrZkClient> zkClientSupplier) throws IOException {
    SolrZkClient zkClient = zkClientSupplier.get();
    try {
      Map<String, Object> clusterPropsJson = (Map<String, Object>) Utils.fromJSON(zkClient.getData(ZkStateReader.CLUSTER_PROPS, null, new Stat(), true));
      return (Map<String, Object>) clusterPropsJson.computeIfAbsent(PLUGIN, o -> new LinkedHashMap<>());
    } catch (KeeperException.NoNodeException e) {
      return new LinkedHashMap<>();
    } catch (KeeperException | InterruptedException e) {
      throw new IOException("Error reading cluster property", SolrZkClient.checkInterrupted(e));
    }
  }

  private void persistPlugins(Function<Map<String,Object>, Map<String,Object>> modifier) throws IOException {
    try {
      zkClientSupplier.get().atomicUpdate(ZkStateReader.CLUSTER_PROPS, bytes -> {
        @SuppressWarnings("unchecked")
        Map<String, Object> rawJson = bytes == null ? new LinkedHashMap<>() :
            (Map<String, Object>) Utils.fromJSON(bytes);
        @SuppressWarnings("unchecked")
        Map<String, Object> pluginsModified = modifier.apply((Map<String, Object>) rawJson.computeIfAbsent(PLUGIN, o -> new LinkedHashMap<>()));
        if (pluginsModified == null) return null;
        rawJson.put(PLUGIN, pluginsModified);
        return Utils.toJSON(rawJson);
      });
    } catch (KeeperException | InterruptedException e) {
      throw new IOException("Error reading cluster property", SolrZkClient.checkInterrupted(e));
    }
  }


}
