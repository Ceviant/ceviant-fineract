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

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MultiTenantTransferRepositoryWrapper {

    private final MultiTenantTransferRepository multiTenantTransferRepository;

    @Autowired
    public MultiTenantTransferRepositoryWrapper(MultiTenantTransferRepository multiTenantTransferRepository) {
        this.multiTenantTransferRepository = multiTenantTransferRepository;
    }

    @Transactional
    public MultiTenantTransferDetails saveMultiTenantTransfer(MultiTenantTransferDetails multiTenantTransferDetails) {
        return this.multiTenantTransferRepository.save(multiTenantTransferDetails);
    }

    @Transactional
    public MultiTenantTransferDetails findByReferenceId(String reference) {
        return this.multiTenantTransferRepository.findByReferenceId(reference);
    }
}
