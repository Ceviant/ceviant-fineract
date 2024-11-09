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

package org.apache.fineract.portfolio.account.service;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.domain.FineractContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.portfolio.account.AccountDetailConstants;
import org.apache.fineract.portfolio.account.api.AccountTransfersApiConstants;
import org.apache.fineract.portfolio.account.domain.MultiTenantTransferDetails;
import org.apache.fineract.portfolio.account.domain.MultiTenantTransferRepository;
import org.apache.fineract.portfolio.account.exception.TransactionUndoNotAllowedException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
public class MultiTenantTransferServiceImpl implements MultiTenantTransferService {

    private final TenantDetailsService tenantDetailsService;

    private final MultiTenantTransferRepository multiTenantTransferRepository;

    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private final FromJsonHelper fromApiJsonHelper;

    @Transactional(propagation = Propagation.REQUIRED)
    @Override
    public CommandProcessingResult transferToAnotherTenant(final JsonCommand command) {

        try {
            final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();
            this.fromApiJsonHelper.checkForUnsupportedParameters(typeOfMap, command.json(), AccountDetailConstants.REQUEST_DATA_PARAMETERS);
            final JsonElement element = this.fromApiJsonHelper.parse(command.json());

            final LocalDate transferDate = command.localDateValueOfParameterNamed(AccountTransfersApiConstants.transferDateParamName);

            Map jsonObject = element.getAsJsonObject().asMap();

            final String fromTenantId = ThreadLocalContextUtil.getTenant().getTenantIdentifier();
            final String toTenantId = getDestinationTenant(jsonObject);

            if (toTenantId.equals(fromTenantId)) {
                throw new TransactionUndoNotAllowedException();
            }
            Long toSavingsAccountId = Long.parseLong(getToSavingsAccountId(jsonObject));
            MultiTenantTransferDetails multiTenantTransferDetails = null;

            // WITHDRAW ON TENANT 1
            withdraw(jsonObject);
            /*
             * Recording Transactions in MultiTenantTransferDetails means that this tenant is the source. We won't
             * record the same transaction in the destination tenant. to avoid duplicates. The reversals should happen
             * from the source tenant. thus getting the destination tenant record. We shouldn't reverse from the
             * destination Tenant.
             *
             * If the destination Tenant wants to reverse the transaction, they should call Do a Multi Tenant Transfer
             * with the reference number.
             */
            multiTenantTransferDetails = saveTransferMetadata(jsonObject, fromTenantId, transferDate);

            // DEPOSIT ON TENANT 2
            depositMoneyToAnotherTenant(toTenantId, jsonObject, toSavingsAccountId, fromTenantId);

            changeTenantDataContext(fromTenantId);

            return new CommandProcessingResultBuilder() //
                    .withCommandId(command.commandId()) //
                    .withReference(String.valueOf(jsonObject.get("reference")).replace("\"", "")) //
                    .withResourceIdAsString(multiTenantTransferDetails.getId().toString()).build();
        } catch (Exception ex) {
            throw ex;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void depositMoneyToAnotherTenant(String toTenantId, Map jsonObject, Long toSavingsAccountId, String fromTenantId) {

        CommandProcessingResult depositResult = null;
        try {
            changeTenantDataContext(toTenantId);
            deposit(jsonObject, toSavingsAccountId);
        } catch (Exception ex) {
            // Rollback WithDraw from Tenant 1
            changeTenantDataContext(fromTenantId);
            Long fromSavingsAccountId = Long.parseLong(getFromSavingsAccountId(jsonObject));
            undoWithdraw(jsonObject, ThreadLocalContextUtil.getTenant(), fromSavingsAccountId, depositResult);
            throw ex;
        }

    }

    private MultiTenantTransferDetails saveTransferMetadata(Map apiJson, String fromTenantId, LocalDate transferDate) {

        Long fromOfficeId = Long.parseLong(apiJson.get("fromOfficeId").toString());
        Long fromClientId = Long.parseLong(apiJson.get("fromClientId").toString());
        Long fromAccountId = Long.parseLong(apiJson.get("fromAccountId").toString());
        Long toClientId = Long.parseLong(apiJson.get("toClientId").toString());
        Long toAccountId = Long.parseLong(apiJson.get("toAccountId").toString());
        Long toOfficeId = Long.parseLong(apiJson.get("toOfficeId").toString());

        BigDecimal transferAmount = null;
        try {
            transferAmount = formatAmount(String.valueOf(apiJson.get("transferAmount")));
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        String toTenantId = String.valueOf(apiJson.get("toTenantId")).replace("\"", "");
        String transferDescription = String.valueOf(apiJson.get("transferDescription")).replace("\"", "");
        String reference = String.valueOf(apiJson.get("reference")).replace("\"", "");

        MultiTenantTransferDetails multiTenantTransferDetails = new MultiTenantTransferDetails(fromOfficeId, fromClientId, fromAccountId,
                toOfficeId, toClientId, toAccountId, fromTenantId, toTenantId, transferDate, transferAmount, transferDescription,
                reference);

        return multiTenantTransferRepository.saveAndFlush(multiTenantTransferDetails);

    }

    private CommandProcessingResult undoWithdraw(Map jsonObject, FineractPlatformTenant fineractPlatformTenant, Long toSavingsAccountId,
            CommandProcessingResult depositResult) {
        ThreadLocalContextUtil.setTenant(fineractPlatformTenant);
        String composeUndoJson = getDepositOrWithDrawlJson(jsonObject);
        Long fromSavingsAccountId = Long.parseLong(getFromSavingsAccountId(jsonObject));
        final CommandWrapperBuilder undoBuilder = new CommandWrapperBuilder().withJson(composeUndoJson).withSavingsId(fromSavingsAccountId)
                .withUseReference(depositResult.resourceId() + "");
        final CommandWrapper commandRequest = undoBuilder
                .undoSavingsAccountTransactionWithReference(toSavingsAccountId, String.valueOf(jsonObject.get("reference")), null, "true")
                .build();
        return this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
    }

    private CommandProcessingResult deposit(Map jsonObject, Long toSavingsAccountId) {
        String composeDepositJson = getDepositOrWithDrawlJson(jsonObject);
        final CommandWrapperBuilder depositBuilder = new CommandWrapperBuilder().withJson(composeDepositJson);
        final CommandWrapper depositCommandRequest = depositBuilder.savingsAccountDeposit(toSavingsAccountId).build();
        return this.commandsSourceWritePlatformService.logCommandSource(depositCommandRequest);
    }

    private CommandProcessingResult withdraw(Map jsonObject) {
        Long fromSavingsAccountId = Long.parseLong(getFromSavingsAccountId(jsonObject));
        String composeWithDrawJson = getDepositOrWithDrawlJson(jsonObject);
        final CommandWrapperBuilder withdrawBuilder = new CommandWrapperBuilder().withJson(composeWithDrawJson);
        final CommandWrapper withDrawCommandRequest = withdrawBuilder.savingsAccountWithdrawal(fromSavingsAccountId).build();
        return this.commandsSourceWritePlatformService.logCommandSource(withDrawCommandRequest);

    }

    private void changeTenantDataContext(String tenantId) {
        FineractPlatformTenant tenant = tenantDetailsService.loadTenantById(tenantId);
        ThreadLocalContextUtil.setTenant(tenant);
        FineractContext context = ThreadLocalContextUtil.getContext();
        ThreadLocalContextUtil.init(context);
    }

    private String getDepositOrWithDrawlJson(Map apiJson) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("locale", apiJson.get("locale"));
        payload.put("dateFormat", apiJson.get("dateFormat"));
        payload.put("transactionDate", apiJson.get("transferDate"));
        payload.put("transactionAmount", apiJson.get("transferAmount"));
        payload.put("receiptNumber", apiJson.get("reference"));
        payload.put("reference", apiJson.get("reference"));
        payload.put("currency", apiJson.get("USD"));
        Gson gson = new Gson();

        return gson.toJson(payload);
    }

    private String getDestinationTenant(Map apiJson) {
        return String.valueOf(apiJson.get("toTenantId")).replace("\"", "");
    }

    private String getFromSavingsAccountId(Map apiJson) {
        return String.valueOf(apiJson.get("fromAccountId"));
    }

    private String getToSavingsAccountId(Map apiJson) {
        return String.valueOf(apiJson.get("toAccountId"));
    }

    private BigDecimal formatAmount(String amount) throws ParseException {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');
        String pattern = "#,##0.0#";
        DecimalFormat decimalFormat = new DecimalFormat(pattern, symbols);
        decimalFormat.setParseBigDecimal(true);
        BigDecimal bigDecimal = (BigDecimal) decimalFormat.parse(amount);
        return bigDecimal;
    }
}
