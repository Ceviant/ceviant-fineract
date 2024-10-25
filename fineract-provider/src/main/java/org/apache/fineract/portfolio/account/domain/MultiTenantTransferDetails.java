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
package org.apache.fineract.portfolio.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

@Entity
@Table(name = "m_multi_tenant_transfer_details")
public class MultiTenantTransferDetails extends AbstractPersistableCustom {

    @Column(name = "from_office_id")
    private Long fromOfficeId;

    @Column(name = "from_client_id")
    private Long fromClientId;

    @Column(name = "from_savings_account_id", nullable = false)
    private Long fromSavingsAccountId;

    @Column(name = "to_office_id")
    private Long toOfficeId;

    @Column(name = "to_client_id")
    private Long toClientId;

    @Column(name = "to_savings_account_id", nullable = false)
    private Long toSavingsAccountId;

    @Column(name = "from_tenant_id", nullable = false)
    private String fromTenantId;

    @Column(name = "to_tenant_id", nullable = false)
    private String toTenantId;

    @Column(name = "transfer_date", nullable = false)
    private LocalDate transferDate;

    @Column(name = "transfer_amount", nullable = false)
    private BigDecimal transferAmount;

    @Column(name = "is_rolled_back", nullable = false)
    private boolean isRolledBack;

    @Column(name = "description")
    private String description;

    @Column(name = "reference")
    private String reference;

    public void setRolledBack(boolean rolledBack) {
        isRolledBack = rolledBack;
    }

    public Long getFromOfficeId() {
        return fromOfficeId;
    }

    public Long getFromClientId() {
        return fromClientId;
    }

    public Long getFromSavingsAccountId() {
        return fromSavingsAccountId;
    }

    public Long getToOfficeId() {
        return toOfficeId;
    }

    public Long getToClientId() {
        return toClientId;
    }

    public Long getToSavingsAccountId() {
        return toSavingsAccountId;
    }

    public String getFromTenantId() {
        return fromTenantId;
    }

    public String getToTenantId() {
        return toTenantId;
    }

    public LocalDate getTransferDate() {
        return transferDate;
    }

    public BigDecimal getTransferAmount() {
        return transferAmount;
    }

    public boolean isRolledBack() {
        return isRolledBack;
    }

    public String getDescription() {
        return description;
    }

    public String getReference() {
        return reference;
    }

    public MultiTenantTransferDetails() {
        super();
    }

    public MultiTenantTransferDetails(Long fromOfficeId, Long fromClientId, Long fromSavingsAccountId, Long toOfficeId, Long toClientId,
            Long toSavingsAccountId, String fromTenantId, String toTenantId, LocalDate transferDate, BigDecimal transferAmount,
            String description, String reference) {
        this.fromOfficeId = fromOfficeId;
        this.fromClientId = fromClientId;
        this.fromSavingsAccountId = fromSavingsAccountId;
        this.toOfficeId = toOfficeId;
        this.toClientId = toClientId;
        this.toSavingsAccountId = toSavingsAccountId;
        this.fromTenantId = fromTenantId;
        this.toTenantId = toTenantId;
        this.transferDate = transferDate;
        this.transferAmount = transferAmount;
        this.description = description;
        this.reference = reference;
    }
}
