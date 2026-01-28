/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fineract.camel.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.camel.data.TransactionStatus;
import org.apache.fineract.camel.data.TransactionStatusTrackingData;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransactionStatusReadPlatformServiceImpl implements TransactionStatusReadPlatformService {

    private final JdbcTemplate jdbcTemplate;
    private static final String BASE_SQL = "SELECT id, transaction_id, operation, status, error_message, created_date, last_modified_date "
            + "FROM m_transaction_status_tracking ";

    public Optional<TransactionStatusTrackingData> findById(String id) {
        try {
            String sql = BASE_SQL + "WHERE id = ?";
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, new Object[] { id }, new int[] { Types.VARCHAR },
                    new TransactionStatusTrackingMapper()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static final class TransactionStatusTrackingMapper implements RowMapper<TransactionStatusTrackingData> {

        @Override
        public TransactionStatusTrackingData mapRow(ResultSet rs, int rowNum) throws SQLException {
            String id = rs.getString("id");
            Long transactionId = rs.getLong("transaction_id");
            String operation = rs.getString("operation");
            String status = rs.getString("status");
            String errorMessage = rs.getString("error_message");
            LocalDateTime createdDate = rs.getTimestamp("created_date").toLocalDateTime();
            LocalDateTime lastModifiedDate = rs.getTimestamp("last_modified_date").toLocalDateTime();

            return TransactionStatusTrackingData.builder().id(id).transactionId(transactionId).operation(operation)
                    .status(TransactionStatus.valueOf(status)).message(errorMessage).createdDate(createdDate)
                    .lastModifiedDate(lastModifiedDate).build();
        }
    }

    @Override
    public Optional<TransactionStatusTrackingData> findByCorrelationId(String id) {
        try {
            String sql = BASE_SQL + "WHERE id = ?";
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, new Object[] { id }, new int[] { Types.VARCHAR },
                    new TransactionStatusTrackingMapper()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
