/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.sys;

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.config.DremioConfig;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.rbac.RbacService;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.parquet.RecordReaderIterator;
import com.dremio.exec.store.pojo.PojoRecordReader;
import com.dremio.exec.store.sys.accesscontrol.AccessControlListingManager;
import com.dremio.exec.store.sys.accesscontrol.SysTableMembershipInfo;
import com.dremio.exec.store.sys.accesscontrol.SysTablePrivilegeInfo;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.sabot.op.scan.ScanOperator;
import com.dremio.sabot.op.spi.ProducerOperator;
import java.util.Iterator;
import java.util.Set;
import java.util.Spliterators;
import java.util.stream.StreamSupport;

/**
 * This class creates batches based on the type of {@link com.dremio.exec.store.sys.SystemTable}.
 */
public class SystemTableScanCreator implements ProducerOperator.Creator<SystemSubScan> {

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Override
  public ProducerOperator create(
      FragmentExecutionContext fec, OperatorContext context, SystemSubScan config)
      throws ExecutionSetupException {
    final SystemTable table = config.getTable();
    final SystemStoragePlugin plugin2 = fec.getStoragePlugin(config.getPluginId());
    Iterator<?> iterator = table.getIterator(plugin2.getSabotContext(), context);

    // Apply user-scoped filtering for RBAC system tables
    iterator = filterRbacSystemTableByUser(iterator, table, plugin2, config);

    final RecordReader reader =
        new PojoRecordReader(
            table.getPojoClass(),
            iterator,
            config.getColumns(),
            context.getTargetBatchSize());

    return new ScanOperator(fec, config, context, RecordReaderIterator.from(reader));
  }

  /**
   * Filters PRIVILEGES and MEMBERSHIP system table rows to the current user's scope when RBAC is
   * enabled and the user is not admin. Admin users and RBAC-disabled deployments see all rows.
   */
  private Iterator<?> filterRbacSystemTableByUser(
      Iterator<?> iterator, SystemTable table, SystemStoragePlugin plugin, SystemSubScan config) {
    // Only filter PRIVILEGES and MEMBERSHIP tables
    if (table != SystemTable.PRIVILEGES && table != SystemTable.MEMBERSHIP) {
      return iterator;
    }

    // Three-way null guard: RBAC must be enabled
    PluginSabotContext sabotContext = plugin.getSabotContext();
    DremioConfig dremioConfig = sabotContext.getDremioConfig();
    AccessControlListingManager aclManager = sabotContext.getAccessControlListingManager();
    if (aclManager == null
        || !(aclManager instanceof RbacService)
        || dremioConfig == null
        || !dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)) {
      return iterator;
    }

    RbacService rbacService = (RbacService) aclManager;

    // Admin sees all rows
    String userName = config.getProps().getUserName();
    if (userName == null || rbacService.isAdminMember(userName)) {
      return iterator;
    }

    // Non-admin: filter rows
    if (table == SystemTable.MEMBERSHIP) {
      return filterMembershipByUser(iterator, userName);
    } else {
      // PRIVILEGES: show grants for user's roles only
      Set<String> userRoleIds = rbacService.getUserRoleIds(userName);
      return filterPrivilegesByUserRoles(iterator, userRoleIds);
    }
  }

  /**
   * Filters sys.membership rows to only show the current user's own memberships.
   */
  @SuppressWarnings("unchecked")
  private Iterator<?> filterMembershipByUser(Iterator<?> iterator, String userName) {
    return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize((Iterator<Object>) iterator, 0), false)
        .filter(
            row -> {
              if (row instanceof SysTableMembershipInfo) {
                return userName.equals(((SysTableMembershipInfo) row).member_name);
              }
              return true;
            })
        .iterator();
  }

  /**
   * Filters sys.privileges rows to only show grants for the user's roles (explicit + PUBLIC).
   */
  @SuppressWarnings("unchecked")
  private Iterator<?> filterPrivilegesByUserRoles(
      Iterator<?> iterator, Set<String> userRoleIds) {
    return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize((Iterator<Object>) iterator, 0), false)
        .filter(
            row -> {
              if (row instanceof SysTablePrivilegeInfo) {
                return userRoleIds.contains(((SysTablePrivilegeInfo) row).grantee);
              }
              return true;
            })
        .iterator();
  }
}
