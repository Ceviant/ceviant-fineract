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

import com.google.gson.Gson;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.exception.InvalidTenantIdentiferException;
import org.apache.fineract.infrastructure.security.domain.TenantDetail;
import org.apache.fineract.portfolio.account.domain.MultiTenantTransferDetails;
import org.apache.fineract.portfolio.account.domain.MultiTenantTransferRepository;
import org.apache.fineract.portfolio.account.exception.TransactionUndoNotAllowedException;
import org.apache.fineract.portfolio.account.exception.TransferNotAllowedException;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class MultiTenantTransferServiceImpl implements MultiTenantTransferService {


    private final TenantDetail tenantDetailsService;

    private final MultiTenantTransferRepository multiTenantTransferRepository;

    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;

    public MultiTenantTransferServiceImpl(final TenantDetail tenantDetailsService,
                                          final MultiTenantTransferRepository multiTenantTransferRepository,
                                          final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService) {

        this.tenantDetailsService = tenantDetailsService;
        this.multiTenantTransferRepository = multiTenantTransferRepository;
        this.commandsSourceWritePlatformService = commandsSourceWritePlatformService;
    }

    @Override
    public CommandProcessingResult transferToAnotherTenant(final JsonCommand command)  {
        Map<String, String> jsonObject = command.mapValueOfParameterNamed("apiRequestBodyAsJson");

        final String fromTenantId = ThreadLocalContextUtil.getTenant().getTenantIdentifier();
        final String toTenantId = getDestinationTenant(jsonObject);

        if (toTenantId.equals(fromTenantId)) {
            throw new TransactionUndoNotAllowedException();
        }
        Long toSavingsAccountId = Long.parseLong(getToSavingsAccountId(jsonObject));
        MultiTenantTransferDetails multiTenantTransferDetails = null;

        // WITHDRAW ON TENANT 1
        CommandProcessingResult withdrawResult = withdraw(jsonObject);
        try {
            multiTenantTransferDetails = saveTransferMetadata(jsonObject, fromTenantId);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        try {
            FineractPlatformTenant fineractPlatformTenant = getFineractPlatformTenant(toTenantId);
            ThreadLocalContextUtil.setTenant(fineractPlatformTenant);
            deposit(jsonObject, toSavingsAccountId);

        } catch (Exception ex) {
            // Rollback WithDrawl
            FineractPlatformTenant fineractPlatformTenant = getFineractPlatformTenant(fromTenantId);
            ThreadLocalContextUtil.setTenant(fineractPlatformTenant);
            Long fromSavingsAccountId = Long.parseLong(getFromSavingsAccountId(jsonObject));
            undoWithdraw(jsonObject, fineractPlatformTenant, fromSavingsAccountId, withdrawResult);
            multiTenantTransferDetails.setRolledBack(true);
            multiTenantTransferRepository.save(multiTenantTransferDetails);
            throw ex;
        }

        // DEPOSIT WITHDRAW ON TENANT 2
        try {
            saveTransferMetadata(jsonObject, fromTenantId);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        FineractPlatformTenant fineractPlatformTenant = getFineractPlatformTenant(fromTenantId);
        ThreadLocalContextUtil.setTenant(fineractPlatformTenant);
//        return composeResponse(jsonObject, fromTenantId, (Long) multiTenantTransferDetails.getId());
        return null;
    }

    private MultiTenantTransferDetails saveTransferMetadata(Map apiJson, String fromTenantId) throws ParseException {
        Long fromOfficeId = ((Integer) apiJson.get("fromOfficeId")).longValue();
        Long fromClientId = ((Integer) apiJson.get("fromClientId")).longValue();
        Long fromAccountId = ((Integer) apiJson.get("fromAccountId")).longValue();
        Long toClientId = ((Integer) apiJson.get("toClientId")).longValue();
        Long toAccountId = ((Integer) apiJson.get("toAccountId")).longValue();
        Long toOfficeId = ((Integer) apiJson.get("toOfficeId")).longValue();
        String dateFormat = String.valueOf(apiJson.get("dateFormat"));
        String transferDate = String.valueOf(apiJson.get("transferDate"));
        BigDecimal transferAmount = formatAmount(String.valueOf(apiJson.get("transferAmount")));
        String toTenantId = String.valueOf(apiJson.get("toTenantId"));
        String transferDescription = String.valueOf(apiJson.get("transferDescription"));
        String reference = String.valueOf(apiJson.get("reference"));

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat);
        MultiTenantTransferDetails multiTenantTransferDetails = new MultiTenantTransferDetails(fromOfficeId, fromClientId, fromAccountId,
                toOfficeId, toClientId, toAccountId, fromTenantId, toTenantId, LocalDate.parse(transferDate, formatter), transferAmount,
                transferDescription, reference);

        return multiTenantTransferRepository.save(multiTenantTransferDetails);

    }

    private String composeResponse(Map apiJson, String fromTenantId, Long resourceId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fromAccountId", apiJson.get("fromAccountId"));
        payload.put("toAccountId", apiJson.get("toAccountId"));
        payload.put("fromTenantId", fromTenantId);
        payload.put("toTenantId", apiJson.get("toTenantId"));
        payload.put("transferAmount", apiJson.get("transferAmount"));
        payload.put("transferDate", apiJson.get("transferDate"));
        payload.put("reference", apiJson.get("reference"));
        payload.put("resourceId", resourceId);
        Gson gson = new Gson();
        return gson.toJson(payload);
    }

    private CommandProcessingResult undoWithdraw(Map jsonObject, FineractPlatformTenant fineractPlatformTenant, Long toSavingsAccountId,
            CommandProcessingResult withdrawResult) {
        ThreadLocalContextUtil.setTenant(fineractPlatformTenant);
        String composeUndoJson = getDepositOrWithDrawlJson(jsonObject);
        Long fromSavingsAccountId = Long.parseLong(getFromSavingsAccountId(jsonObject));
        final CommandWrapperBuilder undoBuilder = new CommandWrapperBuilder().withJson(composeUndoJson).withSavingsId(fromSavingsAccountId)
                .withUseReference(withdrawResult.resourceId() + "");
        final CommandWrapper commandRequest = undoBuilder
                .undoSavingsAccountTransaction(toSavingsAccountId, String.valueOf(jsonObject.get("reference")), null, "true").build();
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

    private FineractPlatformTenant getFineractPlatformTenant(String toTenantId) {
        FineractPlatformTenant fineractPlatformTenant = null;
        try {
            fineractPlatformTenant = tenantDetailsService.loadTenantById(toTenantId);
        } catch (InvalidTenantIdentiferException ex) {
            throw new TransferNotAllowedException(toTenantId);
        }
        return fineractPlatformTenant;
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
        return String.valueOf(apiJson.get("toTenantId"));
    }

    private String getFromSavingsAccountId(Map apiJson) {
        return String.valueOf(apiJson.get("fromAccountId"));
    }

    private String getToSavingsAccountId(Map apiJson) {
        return String.valueOf(apiJson.get("toAccountId"));
    }

    public String undoInterTenantTransfer(String reference) {

        MultiTenantTransferDetails multiTenantTransferDetails = multiTenantTransferRepository.findByReference(reference)
                .orElseThrow(() -> new TransactionUndoNotAllowedException(0L, reference));

        // Rollback Deposit
        undoMultiTenantTransaction(multiTenantTransferDetails, multiTenantTransferDetails.getToSavingsAccountId(),
                multiTenantTransferDetails.getToTenantId());

        // Rollback WithDrawl
        undoMultiTenantTransaction(multiTenantTransferDetails, multiTenantTransferDetails.getFromSavingsAccountId(),
                multiTenantTransferDetails.getFromTenantId());

        Map<String, Object> payload = new HashMap<>();
        payload.put("fromAccountId", multiTenantTransferDetails.getFromSavingsAccountId());
        payload.put("toAccountId", multiTenantTransferDetails.getToSavingsAccountId());
        payload.put("fromTenantId", multiTenantTransferDetails.getFromTenantId());
        payload.put("toTenantId", multiTenantTransferDetails.getToTenantId());
        payload.put("reference", reference);
        payload.put("reversed", true);
        payload.put("reversedAmount", multiTenantTransferDetails.getTransferAmount());
        Gson gson = new Gson();
        return gson.toJson(payload);
    }

    private void undoMultiTenantTransaction(MultiTenantTransferDetails multiTenantTransferDetails, Long savingsAccountId, String tenantId) {
        ThreadLocalContextUtil.setTenant(getFineractPlatformTenant(tenantId));
        final CommandWrapperBuilder undoWithdrawBuilder = new CommandWrapperBuilder().withSavingsId(savingsAccountId)
                .withUseReference(multiTenantTransferDetails.getReference() + "");
        final CommandWrapper commandRequest = undoWithdrawBuilder
                .undoSavingsAccountTransaction(savingsAccountId, multiTenantTransferDetails.getReference(), null, "true").build();
        this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
        MultiTenantTransferDetails newMultiTenantTransferDetails = multiTenantTransferRepository
                .findByReference(multiTenantTransferDetails.getReference())
                .orElseThrow(() -> new TransactionUndoNotAllowedException(0L, multiTenantTransferDetails.getReference()));
        newMultiTenantTransferDetails.setRolledBack(true);
        multiTenantTransferRepository.save(newMultiTenantTransferDetails);
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
