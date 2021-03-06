/**
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

package org.apache.hadoop.hbase.rsgroup;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.constraint.ConstraintException;
import org.apache.hadoop.hbase.coprocessor.BaseMasterObserver;
import org.apache.hadoop.hbase.coprocessor.CoprocessorService;
import org.apache.hadoop.hbase.coprocessor.MasterCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.net.Address;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.ResponseConverter;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.AddRSGroupRequest;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.AddRSGroupResponse;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.BalanceRSGroupRequest;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.BalanceRSGroupResponse;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.GetRSGroupInfoOfServerRequest;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.GetRSGroupInfoOfServerResponse;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.GetRSGroupInfoOfTableRequest;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.GetRSGroupInfoOfTableResponse;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.GetRSGroupInfoRequest;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.GetRSGroupInfoResponse;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.ListRSGroupInfosRequest;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.ListRSGroupInfosResponse;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.MoveServersRequest;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.MoveServersResponse;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.MoveTablesRequest;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.MoveTablesResponse;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.RemoveRSGroupRequest;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.RemoveRSGroupResponse;
import org.apache.hadoop.hbase.protobuf.generated.RSGroupAdminProtos.RSGroupAdminService;

import com.google.common.collect.Sets;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.google.protobuf.Service;

@InterfaceAudience.Private
public class RSGroupAdminEndpoint extends BaseMasterObserver implements CoprocessorService {
  private static final Log LOG = LogFactory.getLog(RSGroupAdminEndpoint.class);

  private MasterServices master = null;
  // Only instance of RSGroupInfoManager. RSGroup aware load balancers ask for this instance on
  // their setup.
  private RSGroupInfoManager groupInfoManager;
  private RSGroupAdminServer groupAdminServer;
  private final RSGroupAdminService groupAdminService = new RSGroupAdminServiceImpl();

  @Override
  public void start(CoprocessorEnvironment env) throws IOException {
    master = ((MasterCoprocessorEnvironment)env).getMasterServices();
    groupInfoManager = RSGroupInfoManagerImpl.getInstance(master);
    groupAdminServer = new RSGroupAdminServer(master, groupInfoManager);
    Class<?> clazz =
        master.getConfiguration().getClass(HConstants.HBASE_MASTER_LOADBALANCER_CLASS, null);
    if (!RSGroupableBalancer.class.isAssignableFrom(clazz)) {
      throw new IOException("Configured balancer does not support RegionServer groups.");
    }
  }

  @Override
  public Service getService() {
    return groupAdminService;
  }

  RSGroupInfoManager getGroupInfoManager() {
    return groupInfoManager;
  }

  /**
   * Implementation of RSGroupAdminService defined in RSGroupAdmin.proto.
   * This class calls {@link RSGroupAdminServer} for actual work, converts result to protocol
   * buffer response, handles exceptions if any occurred and then calls the {@code RpcCallback} with
   * the response.
   * Since our CoprocessorHost asks the Coprocessor for a Service
   * ({@link CoprocessorService#getService()}) instead of doing "coproc instanceOf Service"
   * and requiring Coprocessor itself to be Service (something we do with our observers),
   * we can use composition instead of inheritance here. That makes it easy to manage
   * functionalities in concise classes (sometimes inner classes) instead of single class doing
   * many different things.
   */
  private class RSGroupAdminServiceImpl extends RSGroupAdminProtos.RSGroupAdminService {
    @Override
    public void getRSGroupInfo(RpcController controller,
        GetRSGroupInfoRequest request,
        RpcCallback<GetRSGroupInfoResponse> done) {
      GetRSGroupInfoResponse.Builder builder =
          GetRSGroupInfoResponse.newBuilder();
      String groupName = request.getRSGroupName();
      try {
        RSGroupInfo rsGroupInfo = groupAdminServer.getRSGroupInfo(groupName);
        if (rsGroupInfo != null) {
          builder.setRSGroupInfo(RSGroupProtobufUtil.toProtoGroupInfo(rsGroupInfo));
        }
      } catch (IOException e) {
        ResponseConverter.setControllerException(controller, e);
      }
      done.run(builder.build());
    }

    @Override
    public void getRSGroupInfoOfTable(RpcController controller,
        GetRSGroupInfoOfTableRequest request,
        RpcCallback<GetRSGroupInfoOfTableResponse> done) {
      GetRSGroupInfoOfTableResponse.Builder builder =
          GetRSGroupInfoOfTableResponse.newBuilder();
      try {
        TableName tableName = ProtobufUtil.toTableName(request.getTableName());
        RSGroupInfo RSGroupInfo = groupAdminServer.getRSGroupInfoOfTable(tableName);
        if (RSGroupInfo != null) {
          builder.setRSGroupInfo(RSGroupProtobufUtil.toProtoGroupInfo(RSGroupInfo));
        }
      } catch (IOException e) {
        ResponseConverter.setControllerException(controller, e);
      }
      done.run(builder.build());
    }

    @Override
    public void moveServers(RpcController controller,
        MoveServersRequest request,
        RpcCallback<MoveServersResponse> done) {
      RSGroupAdminProtos.MoveServersResponse.Builder builder =
          RSGroupAdminProtos.MoveServersResponse.newBuilder();
      try {
        Set<Address> hostPorts = Sets.newHashSet();
        for(HBaseProtos.ServerName el: request.getServersList()) {
          hostPorts.add(Address.fromParts(el.getHostName(), el.getPort()));
        }
        groupAdminServer.moveServers(hostPorts, request.getTargetGroup());
      } catch (IOException e) {
        ResponseConverter.setControllerException(controller, e);
      }
      done.run(builder.build());
    }

    @Override
    public void moveTables(RpcController controller,
        MoveTablesRequest request,
        RpcCallback<MoveTablesResponse> done) {
      MoveTablesResponse.Builder builder =
          MoveTablesResponse.newBuilder();
      try {
        Set<TableName> tables = new HashSet<>(request.getTableNameList().size());
        for(HBaseProtos.TableName tableName: request.getTableNameList()) {
          tables.add(ProtobufUtil.toTableName(tableName));
        }
        groupAdminServer.moveTables(tables, request.getTargetGroup());
      } catch (IOException e) {
        ResponseConverter.setControllerException(controller, e);
      }
      done.run(builder.build());
    }

    @Override
    public void addRSGroup(RpcController controller,
        AddRSGroupRequest request,
        RpcCallback<AddRSGroupResponse> done) {
      AddRSGroupResponse.Builder builder =
          AddRSGroupResponse.newBuilder();
      try {
        groupAdminServer.addRSGroup(request.getRSGroupName());
      } catch (IOException e) {
        ResponseConverter.setControllerException(controller, e);
      }
      done.run(builder.build());
    }

    @Override
    public void removeRSGroup(RpcController controller,
        RemoveRSGroupRequest request,
        RpcCallback<RemoveRSGroupResponse> done) {
      RemoveRSGroupResponse.Builder builder =
          RemoveRSGroupResponse.newBuilder();
      try {
        groupAdminServer.removeRSGroup(request.getRSGroupName());
      } catch (IOException e) {
        ResponseConverter.setControllerException(controller, e);
      }
      done.run(builder.build());
    }

    @Override
    public void balanceRSGroup(RpcController controller,
        BalanceRSGroupRequest request,
        RpcCallback<BalanceRSGroupResponse> done) {
      BalanceRSGroupResponse.Builder builder = BalanceRSGroupResponse.newBuilder();
      try {
        builder.setBalanceRan(groupAdminServer.balanceRSGroup(request.getRSGroupName()));
      } catch (IOException e) {
        ResponseConverter.setControllerException(controller, e);
        builder.setBalanceRan(false);
      }
      done.run(builder.build());
    }

    @Override
    public void listRSGroupInfos(RpcController controller,
        ListRSGroupInfosRequest request,
        RpcCallback<ListRSGroupInfosResponse> done) {
      ListRSGroupInfosResponse.Builder builder =
          ListRSGroupInfosResponse.newBuilder();
      try {
        for(RSGroupInfo RSGroupInfo : groupAdminServer.listRSGroups()) {
          builder.addRSGroupInfo(RSGroupProtobufUtil.toProtoGroupInfo(RSGroupInfo));
        }
      } catch (IOException e) {
        ResponseConverter.setControllerException(controller, e);
      }
      done.run(builder.build());
    }

    @Override
    public void getRSGroupInfoOfServer(RpcController controller,
        GetRSGroupInfoOfServerRequest request,
        RpcCallback<GetRSGroupInfoOfServerResponse> done) {
      GetRSGroupInfoOfServerResponse.Builder builder = GetRSGroupInfoOfServerResponse.newBuilder();
      try {
        Address hp =
            Address.fromParts(request.getServer().getHostName(), request.getServer().getPort());
        RSGroupInfo RSGroupInfo = groupAdminServer.getRSGroupOfServer(hp);
        if (RSGroupInfo != null) {
          builder.setRSGroupInfo(RSGroupProtobufUtil.toProtoGroupInfo(RSGroupInfo));
        }
      } catch (IOException e) {
        ResponseConverter.setControllerException(controller, e);
      }
      done.run(builder.build());
    }
  }

  /////////////////////////////////////////////////////////////////////////////
  // MasterObserver overrides
  /////////////////////////////////////////////////////////////////////////////

  // Assign table to default RSGroup.
  @Override
  public void preCreateTable(ObserverContext<MasterCoprocessorEnvironment> ctx,
      HTableDescriptor desc, HRegionInfo[] regions) throws IOException {
    String groupName =
        master.getNamespaceDescriptor(desc.getTableName().getNamespaceAsString())
            .getConfigurationValue(RSGroupInfo.NAMESPACE_DESC_PROP_GROUP);
    if (groupName == null) {
      groupName = RSGroupInfo.DEFAULT_GROUP;
    }
    RSGroupInfo RSGroupInfo = groupAdminServer.getRSGroupInfo(groupName);
    if (RSGroupInfo == null) {
      throw new ConstraintException("RSGroup " + groupName + " does not exist.");
    }
    if (!RSGroupInfo.containsTable(desc.getTableName())) {
      LOG.debug("Pre-moving table " + desc.getTableName() + " to RSGroup " + groupName);
      groupAdminServer.moveTables(Sets.newHashSet(desc.getTableName()), groupName);
    }
  }

  // Remove table from its RSGroup.
  @Override
  public void postDeleteTable(ObserverContext<MasterCoprocessorEnvironment> ctx,
                              TableName tableName) throws IOException {
    try {
      RSGroupInfo group = groupAdminServer.getRSGroupInfoOfTable(tableName);
      if (group != null) {
        LOG.debug("Removing deleted table from table rsgroup " + group.getName());
        groupAdminServer.moveTables(Sets.newHashSet(tableName), null);
      }
    } catch (ConstraintException ex) {
      LOG.debug("Failed to perform RSGroup information cleanup for table: " + tableName, ex);
    } catch (IOException ex) {
      LOG.debug("Failed to perform RSGroup information cleanup for table: " + tableName, ex);
    }
  }

  @Override
  public void preCreateNamespace(ObserverContext<MasterCoprocessorEnvironment> ctx,
                                 NamespaceDescriptor ns) throws IOException {
    String group = ns.getConfigurationValue(RSGroupInfo.NAMESPACE_DESC_PROP_GROUP);
    if(group != null && groupAdminServer.getRSGroupInfo(group) == null) {
      throw new ConstraintException("Region server group "+group+" does not exit");
    }
  }

  @Override
  public void preModifyNamespace(ObserverContext<MasterCoprocessorEnvironment> ctx,
                                 NamespaceDescriptor ns) throws IOException {
    preCreateNamespace(ctx, ns);
  }
}
