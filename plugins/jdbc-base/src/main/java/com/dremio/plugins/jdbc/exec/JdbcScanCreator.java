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
package com.dremio.plugins.jdbc.exec;

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.parquet.RecordReaderIterator;
import com.dremio.plugins.jdbc.JdbcStoragePlugin;
import com.dremio.plugins.jdbc.conf.ProtocolMode;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.sabot.op.scan.ScanOperator;
import com.dremio.sabot.op.spi.ProducerOperator;

/**
 * Binds {@link JdbcSubScan} to the Dremio execution engine by creating a {@link ScanOperator}
 * backed by a {@link com.dremio.plugins.jdbc.reader.JdbcRecordReader} or {@link
 * com.dremio.plugins.jdbc.reader.AdbcRecordReader}, depending on the effective protocol mode.
 *
 * <p>This class is discovered via classpath scanning (see {@code sabot-module.conf}) and wired into
 * the Dremio operator registry as the creator for operators with type {@link
 * com.dremio.exec.proto.UserBitShared.CoreOperatorType#JDBC_SUB_SCAN_VALUE}.
 *
 * <p>Pattern: identical to {@code InfoSchemaScanCreator}.
 */
public class JdbcScanCreator implements ProducerOperator.Creator<JdbcSubScan> {

  @Override
  public ProducerOperator create(
      FragmentExecutionContext fec, OperatorContext context, JdbcSubScan config)
      throws ExecutionSetupException {
    JdbcStoragePlugin plugin = fec.getStoragePlugin(config.getPluginId());
    RecordReader reader;
    if (plugin.getEffectiveProtocolMode() == ProtocolMode.ADBC && plugin.getAdbcFactory() != null) {
      reader = plugin.createAdbcRecordReader(context, config, plugin.getAdbcFactory());
    } else {
      reader = plugin.createRecordReader(context, config, plugin.getPool());
    }
    return new ScanOperator(fec, config, context, RecordReaderIterator.from(reader));
  }
}
