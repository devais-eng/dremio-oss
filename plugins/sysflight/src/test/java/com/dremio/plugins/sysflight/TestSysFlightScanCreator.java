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
package com.dremio.plugins.sysflight;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.config.DremioConfig;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.rbac.RbacService;
import com.dremio.exec.store.sys.SystemTable;
import com.dremio.exec.store.sys.accesscontrol.SysTableMembershipInfo;
import com.dremio.exec.store.sys.accesscontrol.SysTablePrivilegeInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class TestSysFlightScanCreator {

  private SysFlightScanCreator scanCreator;
  private SysFlightStoragePlugin plugin;
  private SysFlightSubScan config;
  private PluginSabotContext sabotContext;
  private DremioConfig dremioConfig;
  private RbacService rbacService;
  private OpProps opProps;

  @Before
  public void setup() {
    scanCreator = new SysFlightScanCreator();
    plugin = mock(SysFlightStoragePlugin.class);
    config = mock(SysFlightSubScan.class);
    sabotContext = mock(PluginSabotContext.class);
    dremioConfig = mock(DremioConfig.class);
    rbacService = mock(RbacService.class);
    opProps = mock(OpProps.class);

    when(plugin.getSabotContext()).thenReturn(sabotContext);
    when(sabotContext.getDremioConfig()).thenReturn(dremioConfig);
    when(sabotContext.getAccessControlListingManager()).thenReturn(rbacService);
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(true);
    when(config.getProps()).thenReturn(opProps);
    when(opProps.getUserName()).thenReturn("alice");
    when(rbacService.isAdminMember("alice")).thenReturn(false);
  }

  @Test
  public void testFilterRbac_membership_nonAdmin_filtersRows() {
    // Three membership rows: alice, bob, charlie
    // Constructor: (role_name, member_name, member_type)
    SysTableMembershipInfo m1 = new SysTableMembershipInfo("role1", "alice", "user");
    SysTableMembershipInfo m2 = new SysTableMembershipInfo("role1", "bob", "user");
    SysTableMembershipInfo m3 = new SysTableMembershipInfo("role2", "charlie", "user");
    Iterator<?> input = Arrays.asList(m1, m2, m3).iterator();

    Iterator<?> result =
        scanCreator.filterRbacSystemTableByUser(input, SystemTable.MEMBERSHIP, plugin, config);

    List<Object> resultList = new ArrayList<>();
    result.forEachRemaining(o -> resultList.add(o));
    assertEquals(1, resultList.size());
    assertEquals(m1, resultList.get(0));
  }

  @Test
  public void testFilterRbac_privileges_nonAdmin_filtersRows() {
    // Three privilege rows with different grantees (role IDs)
    // Constructor: (grantee_type, grantee, object_type, object, privilege)
    // Filtering is by grantee field matching userRoleIds
    SysTablePrivilegeInfo p1 = new SysTablePrivilegeInfo("role", "role1-id", "VDS", "myspace.view1", "SELECT");
    SysTablePrivilegeInfo p2 = new SysTablePrivilegeInfo("role", "role2-id", "VDS", "myspace.view2", "SELECT");
    SysTablePrivilegeInfo p3 = new SysTablePrivilegeInfo("role", "role3-id", "VDS", "myspace.view3", "SELECT");
    Iterator<?> input = Arrays.asList(p1, p2, p3).iterator();

    when(rbacService.getUserRoleIds("alice"))
        .thenReturn(new HashSet<>(Arrays.asList("role1-id", "role3-id")));

    Iterator<?> result =
        scanCreator.filterRbacSystemTableByUser(input, SystemTable.PRIVILEGES, plugin, config);

    List<Object> resultList = new ArrayList<>();
    result.forEachRemaining(o -> resultList.add(o));
    assertEquals(2, resultList.size());
  }

  @Test
  public void testFilterRbac_admin_returnsAllRows() {
    when(rbacService.isAdminMember("alice")).thenReturn(true);
    SysTableMembershipInfo m1 = new SysTableMembershipInfo("role1", "alice", "user");
    SysTableMembershipInfo m2 = new SysTableMembershipInfo("role1", "bob", "user");
    Iterator<?> input = Arrays.asList(m1, m2).iterator();

    Iterator<?> result =
        scanCreator.filterRbacSystemTableByUser(input, SystemTable.MEMBERSHIP, plugin, config);

    List<Object> resultList = new ArrayList<>();
    result.forEachRemaining(o -> resultList.add(o));
    assertEquals(2, resultList.size());
  }

  @Test
  public void testFilterRbac_rbacDisabled_returnsAllRows() {
    when(dremioConfig.getBoolean(DremioConfig.RBAC_ENABLED)).thenReturn(false);
    SysTableMembershipInfo m1 = new SysTableMembershipInfo("role1", "alice", "user");
    SysTableMembershipInfo m2 = new SysTableMembershipInfo("role1", "bob", "user");
    Iterator<?> input = Arrays.asList(m1, m2).iterator();

    Iterator<?> result =
        scanCreator.filterRbacSystemTableByUser(input, SystemTable.MEMBERSHIP, plugin, config);

    List<Object> resultList = new ArrayList<>();
    result.forEachRemaining(o -> resultList.add(o));
    assertEquals(2, resultList.size());
  }

  @Test
  public void testFilterRbac_nonFilteredTable_returnsAllRows() {
    // NODES table is not PRIVILEGES or MEMBERSHIP -- no filtering applied
    Object row1 = new Object();
    Object row2 = new Object();
    Iterator<?> input = Arrays.asList(row1, row2).iterator();

    Iterator<?> result =
        scanCreator.filterRbacSystemTableByUser(input, SystemTable.NODES, plugin, config);

    List<Object> resultList = new ArrayList<>();
    result.forEachRemaining(o -> resultList.add(o));
    assertEquals(2, resultList.size());
  }
}
