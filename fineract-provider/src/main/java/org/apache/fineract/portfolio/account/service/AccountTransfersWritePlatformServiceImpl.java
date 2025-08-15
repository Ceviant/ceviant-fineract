/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.account.service;

import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.AccountTransfersDataValidator;
import org.apache.fineract.portfolio.account.domain.AccountTransferAssembler;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetailRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetails;
import org.apache.fineract.portfolio.account.domain.AccountTransferRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferTransaction;
import org.apache.fineract.portfolio.account.domain.AccountTransferType;
import org.apache.fineract.portfolio.account.domain.MultiTenantTransferDetails;
import org.apache.fineract.portfolio.account.domain.MultiTenantTransferRepository;
import org.apache.fineract.portfolio.account.exception.DifferentCurrenciesException;
import org.apache.fineract.portfolio.account.exception.SavingsAccountTransactionExistsException;
import org.apache.fineract.portfolio.account.exception.TransactionUndoNotAllowedException;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.exception.InvalidPaidInAdvanceAmountException;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.savings.SavingsTransactionBooleanValues;
import org.apache.fineract.portfolio.savings.domain.GSIMRepositoy;
import org.apache.fineract.portfolio.savings.domain.GroupSavingsIndividualMonitoring;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.apache.fineract.portfolio.savings.service.SavingsAccountDomainService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountWritePlatformService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.apache.fineract.portfolio.account.AccountDetailConstants.fromAccountIdParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.fromAccountTypeParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.toAccountIdParamName;
import static org.apache.fineract.portfolio.account.AccountDetailConstants.toAccountTypeParamName;
import static org.apache.fineract.portfolio.account.api.AccountTransfersApiConstants.referenceParamName;
import static org.apache.fineract.portfolio.account.api.AccountTransfersApiConstants.transferAmountParamName;
import static org.apache.fineract.portfolio.account.api.AccountTransfersApiConstants.transferDateParamName;

@RequiredArgsConstructor
public class AccountTransfersWritePlatformServiceImpl implements AccountTransfersWritePlatformService {

    private final AccountTransfersDataValidator accountTransfersDataValidator;
    private final AccountTransferAssembler accountTransferAssembler;
    private final AccountTransferRepository accountTransferRepository;
    private final SavingsAccountAssembler savingsAccountAssembler;
    private final SavingsAccountDomainService savingsAccountDomainService;
    private final LoanAssembler loanAccountAssembler;
    private final LoanAccountDomainService loanAccountDomainService;
    private final SavingsAccountWritePlatformService savingsAccountWritePlatformService;
    private final AccountTransferDetailRepository accountTransferDetailRepository;
    private final LoanReadPlatformService loanReadPlatformService;
    private final GSIMRepositoy gsimRepository;
    private final ConfigurationDomainService configurationDomainService;
    private final ExternalIdFactory externalIdFactory;
    private final FineractProperties fineractProperties;
    private final TenantDetailsService tenantDetailsService;
    private final MultiTenantTransferRepository multiTenantTransferRepository;
    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;

    @Transactional
    @Override
    public CommandProcessingResult create(final JsonCommand command) {
        boolean isRegularTransaction = true;

        this.accountTransfersDataValidator.validate(command);

        final LocalDate transactionDate = command.localDateValueOfParameterNamed(transferDateParamName);
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed(transferAmountParamName);
        final String uniqueTransactionReference = command.stringValueOfParameterNamed(referenceParamName);

        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(command.dateFormat()).withLocale(locale);

        final Integer fromAccountTypeId = command.integerValueSansLocaleOfParameterNamed(fromAccountTypeParamName);
        final PortfolioAccountType fromAccountType = PortfolioAccountType.fromInt(fromAccountTypeId);

        final Integer toAccountTypeId = command.integerValueSansLocaleOfParameterNamed(toAccountTypeParamName);
        final PortfolioAccountType toAccountType = PortfolioAccountType.fromInt(toAccountTypeId);

        final PaymentDetail paymentDetail = null;
        Long fromSavingsAccountId = null;
        Long transferDetailId = null;
        boolean isInterestTransfer = false;
        boolean isAccountTransfer = true;
        Long fromLoanAccountId = null;
        boolean isWithdrawBalance = false;
        final boolean backdatedTxnsAllowedTill = true;

        if (StringUtils.isNotBlank(uniqueTransactionReference)) {
            List<SavingsAccountTransaction> savingsAccountTransactions = savingsAccountTransactionRepository
                    .findByUniqueTransactionReference(uniqueTransactionReference);
            if (!savingsAccountTransactions.isEmpty()) {
                throw new SavingsAccountTransactionExistsException(uniqueTransactionReference);
            }
        }

        if (isSavingsToSavingsAccountTransfer(fromAccountType, toAccountType)) {

            fromSavingsAccountId = command.longValueOfParameterNamed(fromAccountIdParamName);
            final SavingsAccount fromSavingsAccount = this.savingsAccountAssembler.assembleWithLimitedTransacations(fromSavingsAccountId,
                    backdatedTxnsAllowedTill, transactionDate);

            final SavingsTransactionBooleanValues transactionBooleanValues = new SavingsTransactionBooleanValues(isAccountTransfer,
                    isRegularTransaction, fromSavingsAccount.isWithdrawalFeeApplicableForTransfer(), isInterestTransfer, isWithdrawBalance);
            final SavingsAccountTransaction withdrawal = this.savingsAccountDomainService.handleWithdrawal(fromSavingsAccount, fmt,
                    transactionDate, transactionAmount, paymentDetail, transactionBooleanValues, backdatedTxnsAllowedTill);
            withdrawal.setReference(uniqueTransactionReference);

            final Long toSavingsId = command.longValueOfParameterNamed(toAccountIdParamName);
            final SavingsAccount toSavingsAccount = this.savingsAccountAssembler.assembleWithLimitedTransacations(toSavingsId, backdatedTxnsAllowedTill, transactionDate);

            final SavingsAccountTransaction deposit = this.savingsAccountDomainService.handleDeposit(toSavingsAccount, fmt, transactionDate,
                    transactionAmount, paymentDetail, isAccountTransfer, isRegularTransaction, backdatedTxnsAllowedTill);
            deposit.setReference(uniqueTransactionReference);

            if (!fromSavingsAccount.getCurrency().getCode().equals(toSavingsAccount.getCurrency().getCode())) {
                throw new DifferentCurrenciesException(fromSavingsAccount.getCurrency().getCode(),
                        toSavingsAccount.getCurrency().getCode());
            }

            final AccountTransferDetails accountTransferDetails = this.accountTransferAssembler.assembleSavingsToSavingsTransfer(command,
                    fromSavingsAccount, toSavingsAccount, withdrawal, deposit);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferDetailId = accountTransferDetails.getId();

        } else if (isSavingsToLoanAccountTransfer(fromAccountType, toAccountType)) {
            //
            fromSavingsAccountId = command.longValueOfParameterNamed(fromAccountIdParamName);
            final SavingsAccount fromSavingsAccount = this.savingsAccountAssembler.assembleFrom(fromSavingsAccountId,
                    backdatedTxnsAllowedTill);

            final SavingsTransactionBooleanValues transactionBooleanValues = new SavingsTransactionBooleanValues(isAccountTransfer,
                    isRegularTransaction, fromSavingsAccount.isWithdrawalFeeApplicableForTransfer(), isInterestTransfer, isWithdrawBalance);
            final SavingsAccountTransaction withdrawal = this.savingsAccountDomainService.handleWithdrawal(fromSavingsAccount, fmt,
                    transactionDate, transactionAmount, paymentDetail, transactionBooleanValues, backdatedTxnsAllowedTill);

            final Long toLoanAccountId = command.longValueOfParameterNamed(toAccountIdParamName);
            Loan toLoanAccount = this.loanAccountAssembler.assembleFrom(toLoanAccountId);

            final Boolean isHolidayValidationDone = false;
            final HolidayDetailDTO holidayDetailDto = null;
            final boolean isRecoveryRepayment = false;
            final String chargeRefundChargeType = null;

            ExternalId externalId = externalIdFactory.create();
            final LoanTransaction loanRepaymentTransaction = this.loanAccountDomainService.makeRepayment(LoanTransactionType.REPAYMENT,
                    toLoanAccount, transactionDate, transactionAmount, paymentDetail, null, externalId, isRecoveryRepayment,
                    chargeRefundChargeType, isAccountTransfer, holidayDetailDto, isHolidayValidationDone);
            toLoanAccount = loanRepaymentTransaction.getLoan();
            final AccountTransferDetails accountTransferDetails = this.accountTransferAssembler.assembleSavingsToLoanTransfer(command,
                    fromSavingsAccount, toLoanAccount, withdrawal, loanRepaymentTransaction);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferDetailId = accountTransferDetails.getId();

        } else if (isLoanToSavingsAccountTransfer(fromAccountType, toAccountType)) {
            // FIXME - kw - ADD overpaid loan to savings account transfer
            // support.

            fromLoanAccountId = command.longValueOfParameterNamed(fromAccountIdParamName);
            final Loan fromLoanAccount = this.loanAccountAssembler.assembleFrom(fromLoanAccountId);
            ExternalId externalId = externalIdFactory.create();
            final LoanTransaction loanRefundTransaction = this.loanAccountDomainService.makeRefund(fromLoanAccountId,
                    new CommandProcessingResultBuilder(), transactionDate, transactionAmount, paymentDetail, null, externalId);

            final Long toSavingsAccountId = command.longValueOfParameterNamed(toAccountIdParamName);
            final SavingsAccount toSavingsAccount = this.savingsAccountAssembler.assembleFrom(toSavingsAccountId, backdatedTxnsAllowedTill);

            final SavingsAccountTransaction deposit = this.savingsAccountDomainService.handleDeposit(toSavingsAccount, fmt, transactionDate,
                    transactionAmount, paymentDetail, isAccountTransfer, isRegularTransaction, backdatedTxnsAllowedTill);
            deposit.setReference(uniqueTransactionReference);

            final AccountTransferDetails accountTransferDetails = this.accountTransferAssembler.assembleLoanToSavingsTransfer(command,
                    fromLoanAccount, toSavingsAccount, deposit, loanRefundTransaction);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferDetailId = accountTransferDetails.getId();

        }

        final CommandProcessingResultBuilder builder = new CommandProcessingResultBuilder().withEntityId(transferDetailId)
                .withReference(uniqueTransactionReference);

        if (fromAccountType.isSavingsAccount()) {
            builder.withSavingsId(fromSavingsAccountId);
        }
        if (fromAccountType.isLoanAccount()) {
            builder.withLoanId(fromLoanAccountId);
        }

        return builder.build();
    }

    @Override
    @Transactional
    public void reverseTransfersWithFromAccountType(final Long accountNumber, final PortfolioAccountType accountTypeId) {
        List<AccountTransferTransaction> acccountTransfers = null;
        if (accountTypeId.isLoanAccount()) {
            acccountTransfers = this.accountTransferRepository.findByFromLoanId(accountNumber);
        }
        if (acccountTransfers != null && acccountTransfers.size() > 0) {
            undoTransactions(acccountTransfers);
        }

    }

    @Override
    @Transactional
    public void reverseTransfersWithFromAccountTransactions(final Collection<Long> fromTransactionIds,
                                                            final PortfolioAccountType accountTypeId) {
        List<AccountTransferTransaction> acccountTransfers = new ArrayList<>();
        if (accountTypeId.isLoanAccount()) {
            List<List<Long>> partitions = Lists.partition(fromTransactionIds.stream().toList(),
                    fineractProperties.getQuery().getInClauseParameterSizeLimit());
            partitions.forEach(partition -> acccountTransfers.addAll(this.accountTransferRepository.findByFromLoanTransactions(partition)));
        }
        if (acccountTransfers.size() > 0) {
            undoTransactions(acccountTransfers);
        }

    }

    @Override
    @Transactional
    public void reverseAllTransactions(final Long accountId, final PortfolioAccountType accountTypeId) {
        List<AccountTransferTransaction> acccountTransfers = null;
        if (accountTypeId.isLoanAccount()) {
            acccountTransfers = this.accountTransferRepository.findAllByLoanId(accountId);
        }
        if (acccountTransfers != null && acccountTransfers.size() > 0) {
            undoTransactions(acccountTransfers);
        }
    }

    /**
     * @param acccountTransfers
     */
    private void undoTransactions(final List<AccountTransferTransaction> acccountTransfers) {
        for (final AccountTransferTransaction accountTransfer : acccountTransfers) {
            if (accountTransfer.getFromLoanTransaction() != null) {
                this.loanAccountDomainService.reverseTransfer(accountTransfer.getFromLoanTransaction());
            }
            if (accountTransfer.getToLoanTransaction() != null) {
                this.loanAccountDomainService.reverseTransfer(accountTransfer.getToLoanTransaction());
            }
            if (accountTransfer.getFromTransaction() != null) {
                this.savingsAccountWritePlatformService.undoTransaction(
                        accountTransfer.accountTransferDetails().fromSavingsAccount().getId(), accountTransfer.getFromTransaction().getId(),
                        true);
            }
            if (accountTransfer.getToSavingsTransaction() != null) {
                this.savingsAccountWritePlatformService.undoTransaction(accountTransfer.accountTransferDetails().toSavingsAccount().getId(),
                        accountTransfer.getToSavingsTransaction().getId(), true);
            }
            accountTransfer.reverse();
            this.accountTransferRepository.save(accountTransfer);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long transferFundsWithTransactions(final AccountTransferDTO accountTransferDTO) {
        return transferFunds(accountTransferDTO);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long transferFundsWithTransactionsMaturityDetailsJob(final AccountTransferDTO accountTransferDTO) {

        Long transferTransactionId = null;
        final boolean isAccountTransfer = true;
        final boolean isRegularTransaction = accountTransferDTO.isRegularTransaction();
        final boolean backdatedTxnsAllowedTill = false;
        AccountTransferDetails accountTransferDetails = accountTransferDTO.getAccountTransferDetails();
        if (isSavingsToLoanAccountTransfer(accountTransferDTO.getFromAccountType(), accountTransferDTO.getToAccountType())) {
            //
            SavingsAccount fromSavingsAccount = null;
            Loan toLoanAccount = null;
            if (accountTransferDetails == null) {
                if (accountTransferDTO.getFromSavingsAccount() == null) {
                    fromSavingsAccount = this.savingsAccountAssembler.assembleFrom(accountTransferDTO.getFromAccountId(),
                            backdatedTxnsAllowedTill);
                } else {
                    fromSavingsAccount = accountTransferDTO.getFromSavingsAccount();
                    this.savingsAccountAssembler.setHelpers(fromSavingsAccount);
                }
                if (accountTransferDTO.getLoan() == null) {
                    toLoanAccount = this.loanAccountAssembler.assembleFrom(accountTransferDTO.getToAccountId());
                } else {
                    toLoanAccount = accountTransferDTO.getLoan();
                    this.loanAccountAssembler.setHelpers(toLoanAccount);
                }

            } else {
                fromSavingsAccount = accountTransferDetails.fromSavingsAccount();
                this.savingsAccountAssembler.setHelpers(fromSavingsAccount);
                toLoanAccount = accountTransferDetails.toLoanAccount();
                this.loanAccountAssembler.setHelpers(toLoanAccount);
            }

            final SavingsTransactionBooleanValues transactionBooleanValues = new SavingsTransactionBooleanValues(isAccountTransfer,
                    isRegularTransaction, fromSavingsAccount.isWithdrawalFeeApplicableForTransfer(),
                    AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isInterestTransfer(),
                    accountTransferDTO.isExceptionForBalanceCheck());

            final SavingsAccountTransaction withdrawal = this.savingsAccountDomainService.handleWithdrawal(fromSavingsAccount,
                    accountTransferDTO.getFmt(), accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                    accountTransferDTO.getPaymentDetail(), transactionBooleanValues, backdatedTxnsAllowedTill);

            LoanTransaction loanTransaction;

            ExternalId txnExternalId = accountTransferDTO.getTxnExternalId();
            // Safety net (it might need to generate new one)
            ExternalId externalId = externalIdFactory.create(txnExternalId.getValue());

            if (AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isChargePayment()) {
                loanTransaction = this.loanAccountDomainService.makeChargePayment(toLoanAccount, accountTransferDTO.getChargeId(),
                        accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                        accountTransferDTO.getPaymentDetail(), null, externalId, accountTransferDTO.getToTransferType(),
                        accountTransferDTO.getLoanInstallmentNumber());

            } else if (AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isLoanDownPayment()) {
                final boolean isRecoveryRepayment = false;
                final Boolean isHolidayValidationDone = false;
                final HolidayDetailDTO holidayDetailDto = null;
                final String chargeRefundChargeType = null;
                loanTransaction = this.loanAccountDomainService.makeRepayment(LoanTransactionType.DOWN_PAYMENT, toLoanAccount,
                        accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                        accountTransferDTO.getPaymentDetail(), null, externalId, isRecoveryRepayment, chargeRefundChargeType,
                        isAccountTransfer, holidayDetailDto, isHolidayValidationDone);
                toLoanAccount = loanTransaction.getLoan();
            } else {
                final boolean isRecoveryRepayment = false;
                final Boolean isHolidayValidationDone = false;
                final HolidayDetailDTO holidayDetailDto = null;
                final String chargeRefundChargeType = null;
                loanTransaction = this.loanAccountDomainService.makeRepayment(LoanTransactionType.REPAYMENT, toLoanAccount,
                        accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                        accountTransferDTO.getPaymentDetail(), null, externalId, isRecoveryRepayment, chargeRefundChargeType,
                        isAccountTransfer, holidayDetailDto, isHolidayValidationDone);
                toLoanAccount = loanTransaction.getLoan();
            }

            accountTransferDetails = this.accountTransferAssembler.assembleSavingsToLoanTransfer(accountTransferDTO, fromSavingsAccount,
                    toLoanAccount, withdrawal, loanTransaction);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferTransactionId = accountTransferDetails.getId();
        } else if (isSavingsToSavingsAccountTransfer(accountTransferDTO.getFromAccountType(), accountTransferDTO.getToAccountType())) {

            SavingsAccount fromSavingsAccount;
            SavingsAccount toSavingsAccount;
            if (accountTransferDetails == null) {
                if (accountTransferDTO.getFromSavingsAccount() == null) {
                    fromSavingsAccount = this.savingsAccountAssembler.assembleFrom(accountTransferDTO.getFromAccountId(),
                            backdatedTxnsAllowedTill);
                } else {
                    fromSavingsAccount = accountTransferDTO.getFromSavingsAccount();
                    this.savingsAccountAssembler.setHelpers(fromSavingsAccount);
                }
                if (accountTransferDTO.getToSavingsAccount() == null) {
                    toSavingsAccount = this.savingsAccountAssembler.assembleFrom(accountTransferDTO.getToAccountId(), false);
                } else {
                    toSavingsAccount = accountTransferDTO.getToSavingsAccount();
                    this.savingsAccountAssembler.setHelpers(toSavingsAccount);
                }
            } else {
                fromSavingsAccount = accountTransferDetails.fromSavingsAccount();
                this.savingsAccountAssembler.setHelpers(fromSavingsAccount);
                toSavingsAccount = accountTransferDetails.toSavingsAccount();
                this.savingsAccountAssembler.setHelpers(toSavingsAccount);
            }

            final SavingsTransactionBooleanValues transactionBooleanValues = new SavingsTransactionBooleanValues(isAccountTransfer,
                    isRegularTransaction, fromSavingsAccount.isWithdrawalFeeApplicableForTransfer(),
                    AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isInterestTransfer(),
                    accountTransferDTO.isExceptionForBalanceCheck());

            LocalDate transactionDate = accountTransferDTO.getTransactionDate();
            if (configurationDomainService.isSavingsInterestPostingAtCurrentPeriodEnd()
                    && configurationDomainService.isNextDayFixedDepositInterestTransferEnabledForPeriodEnd()
                    && AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isInterestTransfer()) {
                transactionDate = transactionDate.plusDays(1);
            }

            final SavingsAccountTransaction withdrawal = this.savingsAccountDomainService.handleWithdrawalWithMaturityDetailsJob(
                    fromSavingsAccount, accountTransferDTO.getFmt(), transactionDate, accountTransferDTO.getTransactionAmount(),
                    accountTransferDTO.getPaymentDetail(), transactionBooleanValues, backdatedTxnsAllowedTill);

            final SavingsAccountTransaction deposit = this.savingsAccountDomainService.handleDeposit(toSavingsAccount,
                    accountTransferDTO.getFmt(), transactionDate, accountTransferDTO.getTransactionAmount(),
                    accountTransferDTO.getPaymentDetail(), isAccountTransfer, isRegularTransaction, backdatedTxnsAllowedTill);

            accountTransferDetails = this.accountTransferAssembler.assembleSavingsToSavingsTransfer(accountTransferDTO, fromSavingsAccount,
                    toSavingsAccount, withdrawal, deposit);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferTransactionId = accountTransferDetails.getId();

        } else if (isLoanToSavingsAccountTransfer(accountTransferDTO.getFromAccountType(), accountTransferDTO.getToAccountType())) {

            Loan fromLoanAccount = null;
            SavingsAccount toSavingsAccount = null;
            if (accountTransferDetails == null) {
                if (accountTransferDTO.getLoan() == null) {
                    fromLoanAccount = this.loanAccountAssembler.assembleFrom(accountTransferDTO.getFromAccountId());
                } else {
                    fromLoanAccount = accountTransferDTO.getLoan();
                    this.loanAccountAssembler.setHelpers(fromLoanAccount);
                }
                toSavingsAccount = this.savingsAccountAssembler.assembleFrom(accountTransferDTO.getToAccountId(), backdatedTxnsAllowedTill);
            } else {
                fromLoanAccount = accountTransferDetails.fromLoanAccount();
                this.loanAccountAssembler.setHelpers(fromLoanAccount);
                toSavingsAccount = accountTransferDetails.toSavingsAccount();
                this.savingsAccountAssembler.setHelpers(toSavingsAccount);
            }
            LoanTransaction loanTransaction = null;

            ExternalId txnExternalId = accountTransferDTO.getTxnExternalId();
            // Safety net (it might need to generate new one)
            ExternalId externalId = externalIdFactory.create(txnExternalId.getValue());

            if (LoanTransactionType.DISBURSEMENT.getValue().equals(accountTransferDTO.getFromTransferType())) {
                loanTransaction = this.loanAccountDomainService.makeDisburseTransaction(accountTransferDTO.getFromAccountId(),
                        accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                        accountTransferDTO.getPaymentDetail(), accountTransferDTO.getNoteText(), externalId);
            } else {
                loanTransaction = this.loanAccountDomainService.makeRefund(accountTransferDTO.getFromAccountId(),
                        new CommandProcessingResultBuilder(), accountTransferDTO.getTransactionDate(),
                        accountTransferDTO.getTransactionAmount(), accountTransferDTO.getPaymentDetail(), accountTransferDTO.getNoteText(),
                        externalId);
            }

            final SavingsAccountTransaction deposit = this.savingsAccountDomainService.handleDeposit(toSavingsAccount,
                    accountTransferDTO.getFmt(), accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                    accountTransferDTO.getPaymentDetail(), isAccountTransfer, isRegularTransaction, backdatedTxnsAllowedTill);
            accountTransferDetails = this.accountTransferAssembler.assembleLoanToSavingsTransfer(accountTransferDTO, fromLoanAccount,
                    toSavingsAccount, deposit, loanTransaction);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferTransactionId = accountTransferDetails.getId();

            // if the savings account is GSIM, update its parent as well
            if (toSavingsAccount.getGsim() != null) {
                GroupSavingsIndividualMonitoring gsim = gsimRepository.findById(toSavingsAccount.getGsim().getId()).orElseThrow();
                BigDecimal currentBalance = gsim.getParentDeposit();
                BigDecimal newBalance = currentBalance.add(accountTransferDTO.getTransactionAmount());
                gsim.setParentDeposit(newBalance);
                gsimRepository.save(gsim);
            }
        } else {
            throw new GeneralPlatformDomainRuleException("error.msg.accounttransfer.loan.to.loan.not.supported",
                    "Account transfer from loan to another loan is not supported");
        }

        return transferTransactionId;
    }

    @Override
    public Long transferFunds(final AccountTransferDTO accountTransferDTO) {
        Long transferTransactionId = null;
        final boolean isAccountTransfer = true;
        final boolean isRegularTransaction = accountTransferDTO.isRegularTransaction();
        final boolean backdatedTxnsAllowedTill = false;
        AccountTransferDetails accountTransferDetails = accountTransferDTO.getAccountTransferDetails();
        if (isSavingsToLoanAccountTransfer(accountTransferDTO.getFromAccountType(), accountTransferDTO.getToAccountType())) {
            //
            SavingsAccount fromSavingsAccount = null;
            Loan toLoanAccount = null;
            if (accountTransferDetails == null) {
                if (accountTransferDTO.getFromSavingsAccount() == null) {
                    fromSavingsAccount = this.savingsAccountAssembler.assembleWithLimitedTransacations(accountTransferDTO.getFromAccountId(),
                            backdatedTxnsAllowedTill, accountTransferDTO.getTransactionDate());
                } else {
                    fromSavingsAccount = accountTransferDTO.getFromSavingsAccount();
                    this.savingsAccountAssembler.setHelpers(fromSavingsAccount);
                }
                if (accountTransferDTO.getLoan() == null) {
                    toLoanAccount = this.loanAccountAssembler.assembleFrom(accountTransferDTO.getToAccountId());
                } else {
                    toLoanAccount = accountTransferDTO.getLoan();
                    this.loanAccountAssembler.setHelpers(toLoanAccount);
                }

            } else {
                fromSavingsAccount = accountTransferDetails.fromSavingsAccount();
                this.savingsAccountAssembler.setHelpers(fromSavingsAccount);
                toLoanAccount = accountTransferDetails.toLoanAccount();
                this.loanAccountAssembler.setHelpers(toLoanAccount);
            }

            final SavingsTransactionBooleanValues transactionBooleanValues = new SavingsTransactionBooleanValues(isAccountTransfer,
                    isRegularTransaction, fromSavingsAccount.isWithdrawalFeeApplicableForTransfer(),
                    AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isInterestTransfer(),
                    accountTransferDTO.isExceptionForBalanceCheck());

            final SavingsAccountTransaction withdrawal = this.savingsAccountDomainService.handleWithdrawal(fromSavingsAccount,
                    accountTransferDTO.getFmt(), accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                    accountTransferDTO.getPaymentDetail(), transactionBooleanValues, backdatedTxnsAllowedTill);

            LoanTransaction loanTransaction;

            ExternalId txnExternalId = accountTransferDTO.getTxnExternalId();
            // Safety net (it might need to generate new one)
            ExternalId externalId = externalIdFactory.create(txnExternalId.getValue());

            if (AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isChargePayment()) {
                loanTransaction = this.loanAccountDomainService.makeChargePayment(toLoanAccount, accountTransferDTO.getChargeId(),
                        accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                        accountTransferDTO.getPaymentDetail(), null, externalId, accountTransferDTO.getToTransferType(),
                        accountTransferDTO.getLoanInstallmentNumber());

            } else if (AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isLoanDownPayment()) {
                final boolean isRecoveryRepayment = false;
                final Boolean isHolidayValidationDone = false;
                final HolidayDetailDTO holidayDetailDto = null;
                final String chargeRefundChargeType = null;
                loanTransaction = this.loanAccountDomainService.makeRepayment(LoanTransactionType.DOWN_PAYMENT, toLoanAccount,
                        accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                        accountTransferDTO.getPaymentDetail(), null, externalId, isRecoveryRepayment, chargeRefundChargeType,
                        isAccountTransfer, holidayDetailDto, isHolidayValidationDone);
                toLoanAccount = loanTransaction.getLoan();
            } else {
                final boolean isRecoveryRepayment = false;
                final Boolean isHolidayValidationDone = false;
                final HolidayDetailDTO holidayDetailDto = null;
                final String chargeRefundChargeType = null;
                loanTransaction = this.loanAccountDomainService.makeRepayment(LoanTransactionType.REPAYMENT, toLoanAccount,
                        accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                        accountTransferDTO.getPaymentDetail(), null, externalId, isRecoveryRepayment, chargeRefundChargeType,
                        isAccountTransfer, holidayDetailDto, isHolidayValidationDone);
                toLoanAccount = loanTransaction.getLoan();
            }

            accountTransferDetails = this.accountTransferAssembler.assembleSavingsToLoanTransfer(accountTransferDTO, fromSavingsAccount,
                    toLoanAccount, withdrawal, loanTransaction);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferTransactionId = accountTransferDetails.getId();
        } else if (isSavingsToSavingsAccountTransfer(accountTransferDTO.getFromAccountType(), accountTransferDTO.getToAccountType())) {

            SavingsAccount fromSavingsAccount;
            SavingsAccount toSavingsAccount;
            if (accountTransferDetails == null) {
                if (accountTransferDTO.getFromSavingsAccount() == null) {
                    fromSavingsAccount = this.savingsAccountAssembler.assembleWithLimitedTransacations(accountTransferDTO.getFromAccountId(),
                            backdatedTxnsAllowedTill, accountTransferDTO.getTransactionDate());
                } else {
                    fromSavingsAccount = accountTransferDTO.getFromSavingsAccount();
                    this.savingsAccountAssembler.setHelpers(fromSavingsAccount);
                }
                if (accountTransferDTO.getToSavingsAccount() == null) {
                    toSavingsAccount = this.savingsAccountAssembler.assembleWithLimitedTransacations(accountTransferDTO.getToAccountId(), false, accountTransferDTO.getTransactionDate());
                } else {
                    toSavingsAccount = accountTransferDTO.getToSavingsAccount();
                    this.savingsAccountAssembler.setHelpers(toSavingsAccount);
                }
            } else {
                fromSavingsAccount = accountTransferDetails.fromSavingsAccount();
                this.savingsAccountAssembler.setHelpers(fromSavingsAccount);
                toSavingsAccount = accountTransferDetails.toSavingsAccount();
                this.savingsAccountAssembler.setHelpers(toSavingsAccount);
            }

            final SavingsTransactionBooleanValues transactionBooleanValues = new SavingsTransactionBooleanValues(isAccountTransfer,
                    isRegularTransaction, fromSavingsAccount.isWithdrawalFeeApplicableForTransfer(),
                    AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isInterestTransfer(),
                    accountTransferDTO.isExceptionForBalanceCheck());

            LocalDate transactionDate = accountTransferDTO.getTransactionDate();
            if (configurationDomainService.isSavingsInterestPostingAtCurrentPeriodEnd()
                    && configurationDomainService.isNextDayFixedDepositInterestTransferEnabledForPeriodEnd()
                    && AccountTransferType.fromInt(accountTransferDTO.getTransferType()).isInterestTransfer()) {
                transactionDate = transactionDate.plusDays(1);
            }

            final SavingsAccountTransaction withdrawal = this.savingsAccountDomainService.handleWithdrawal(fromSavingsAccount,
                    accountTransferDTO.getFmt(), transactionDate, accountTransferDTO.getTransactionAmount(),
                    accountTransferDTO.getPaymentDetail(), transactionBooleanValues, backdatedTxnsAllowedTill);

            final SavingsAccountTransaction deposit = this.savingsAccountDomainService.handleDeposit(toSavingsAccount,
                    accountTransferDTO.getFmt(), transactionDate, accountTransferDTO.getTransactionAmount(),
                    accountTransferDTO.getPaymentDetail(), isAccountTransfer, isRegularTransaction, backdatedTxnsAllowedTill);

            accountTransferDetails = this.accountTransferAssembler.assembleSavingsToSavingsTransfer(accountTransferDTO, fromSavingsAccount,
                    toSavingsAccount, withdrawal, deposit);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferTransactionId = accountTransferDetails.getId();

        } else if (isLoanToSavingsAccountTransfer(accountTransferDTO.getFromAccountType(), accountTransferDTO.getToAccountType())) {

            Loan fromLoanAccount = null;
            SavingsAccount toSavingsAccount = null;
            if (accountTransferDetails == null) {
                if (accountTransferDTO.getLoan() == null) {
                    fromLoanAccount = this.loanAccountAssembler.assembleFrom(accountTransferDTO.getFromAccountId());
                } else {
                    fromLoanAccount = accountTransferDTO.getLoan();
                    this.loanAccountAssembler.setHelpers(fromLoanAccount);
                }
                toSavingsAccount = this.savingsAccountAssembler.assembleWithLimitedTransacations(accountTransferDTO.getToAccountId(), backdatedTxnsAllowedTill, accountTransferDTO.getTransactionDate());
            } else {
                fromLoanAccount = accountTransferDetails.fromLoanAccount();
                this.loanAccountAssembler.setHelpers(fromLoanAccount);
                toSavingsAccount = accountTransferDetails.toSavingsAccount();
                this.savingsAccountAssembler.setHelpers(toSavingsAccount);
            }
            LoanTransaction loanTransaction = null;

            ExternalId txnExternalId = accountTransferDTO.getTxnExternalId();
            // Safety net (it might need to generate new one)
            ExternalId externalId = externalIdFactory.create(txnExternalId.getValue());

            if (LoanTransactionType.DISBURSEMENT.getValue().equals(accountTransferDTO.getFromTransferType())) {
                loanTransaction = this.loanAccountDomainService.makeDisburseTransaction(accountTransferDTO.getFromAccountId(),
                        accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                        accountTransferDTO.getPaymentDetail(), accountTransferDTO.getNoteText(), externalId);
            } else {
                loanTransaction = this.loanAccountDomainService.makeRefund(accountTransferDTO.getFromAccountId(),
                        new CommandProcessingResultBuilder(), accountTransferDTO.getTransactionDate(),
                        accountTransferDTO.getTransactionAmount(), accountTransferDTO.getPaymentDetail(), accountTransferDTO.getNoteText(),
                        externalId);
            }

            final SavingsAccountTransaction deposit = this.savingsAccountDomainService.handleDeposit(toSavingsAccount,
                    accountTransferDTO.getFmt(), accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(),
                    accountTransferDTO.getPaymentDetail(), isAccountTransfer, isRegularTransaction, backdatedTxnsAllowedTill);
            accountTransferDetails = this.accountTransferAssembler.assembleLoanToSavingsTransfer(accountTransferDTO, fromLoanAccount,
                    toSavingsAccount, deposit, loanTransaction);
            this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
            transferTransactionId = accountTransferDetails.getId();

            // if the savings account is GSIM, update its parent as well
            if (toSavingsAccount.getGsim() != null) {
                GroupSavingsIndividualMonitoring gsim = gsimRepository.findById(toSavingsAccount.getGsim().getId()).orElseThrow();
                BigDecimal currentBalance = gsim.getParentDeposit();
                BigDecimal newBalance = currentBalance.add(accountTransferDTO.getTransactionAmount());
                gsim.setParentDeposit(newBalance);
                gsimRepository.save(gsim);
            }
        } else {
            throw new GeneralPlatformDomainRuleException("error.msg.accounttransfer.loan.to.loan.not.supported",
                    "Account transfer from loan to another loan is not supported");
        }

        return transferTransactionId;
    }

    @Override
    public AccountTransferDetails repayLoanWithTopup(AccountTransferDTO accountTransferDTO) {
        final boolean isAccountTransfer = true;
        Loan fromLoanAccount = null;
        if (accountTransferDTO.getFromLoan() == null) {
            fromLoanAccount = this.loanAccountAssembler.assembleFrom(accountTransferDTO.getFromAccountId());
        } else {
            fromLoanAccount = accountTransferDTO.getFromLoan();
            this.loanAccountAssembler.setHelpers(fromLoanAccount);
        }
        Loan toLoanAccount = null;
        if (accountTransferDTO.getToLoan() == null) {
            toLoanAccount = this.loanAccountAssembler.assembleFrom(accountTransferDTO.getToAccountId());
        } else {
            toLoanAccount = accountTransferDTO.getToLoan();
            this.loanAccountAssembler.setHelpers(toLoanAccount);
        }

        ExternalId externalIdForDisbursement = accountTransferDTO.getTxnExternalId();

        LoanTransaction disburseTransaction = this.loanAccountDomainService.makeDisburseTransaction(accountTransferDTO.getFromAccountId(),
                accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(), accountTransferDTO.getPaymentDetail(),
                accountTransferDTO.getNoteText(), externalIdForDisbursement, true);
        final String chargeRefundChargeType = null;

        ExternalId externalIdForRepayment = externalIdFactory.create();

        LoanTransaction repayTransaction = this.loanAccountDomainService.makeRepayment(LoanTransactionType.REPAYMENT, toLoanAccount,
                accountTransferDTO.getTransactionDate(), accountTransferDTO.getTransactionAmount(), accountTransferDTO.getPaymentDetail(),
                null, externalIdForRepayment, false, chargeRefundChargeType, isAccountTransfer, null, false, true);

        AccountTransferDetails accountTransferDetails = this.accountTransferAssembler.assembleLoanToLoanTransfer(accountTransferDTO,
                fromLoanAccount, toLoanAccount, disburseTransaction, repayTransaction);
        this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);

        return accountTransferDetails;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public CommandProcessingResult undoInterTenantTransfer(String reference) {
        /*
         *
         * This should reverse a transaction basing from the source Tenant to the destination Tenant. If the receipt
         * tries to reverse the transaction, it should be rejected. since in the Destination Tenant we don't record in
         * multiTenantTransferDetails table.
         *
         * If the Destination Tenant wants to reverse the transaction, They need to make a Transfer back to the source
         * Tenant. but they don't have the right to reverse the transaction.
         *
         */

        MultiTenantTransferDetails multiTenantTransferDetails = multiTenantTransferRepository.findByReference(reference)
                .orElseThrow(() -> new TransactionUndoNotAllowedException(0L, reference));

        // Rollback Deposit Destination
        changeTenantDataContext(multiTenantTransferDetails.getToTenantId());

        undoMultiTenantTransaction(multiTenantTransferDetails, multiTenantTransferDetails.getToSavingsAccountId(),
                multiTenantTransferDetails.getToTenantId(), false);

        // Rollback WithDrawl Source
        changeTenantDataContext(multiTenantTransferDetails.getFromTenantId());

        undoMultiTenantTransaction(multiTenantTransferDetails, multiTenantTransferDetails.getFromSavingsAccountId(),
                multiTenantTransferDetails.getFromTenantId(), true);
        // change back the default Tenant

        changeTenantDataContext(multiTenantTransferDetails.getFromTenantId());

        Map<String, Object> payload = new HashMap<>();
        payload.put("fromAccountId", multiTenantTransferDetails.getFromSavingsAccountId());
        payload.put("toAccountId", multiTenantTransferDetails.getToSavingsAccountId());
        payload.put("fromTenantId", multiTenantTransferDetails.getFromTenantId());
        payload.put("toTenantId", multiTenantTransferDetails.getToTenantId());
        payload.put("reference", reference);
        payload.put("reversed", true);
        payload.put("reversedAmount", multiTenantTransferDetails.getTransferAmount());
        final CommandProcessingResultBuilder builder = new CommandProcessingResultBuilder()
                .withEntityId(multiTenantTransferDetails.getFromSavingsAccountId()).with(payload).withReference(reference);
        return builder.build();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void undoMultiTenantTransaction(MultiTenantTransferDetails multiTenantTransferDetails, Long savingsAccountId, String tenantId,
                                            Boolean isSourceTenant) {

        final CommandWrapperBuilder undoWithdrawBuilder = new CommandWrapperBuilder().withSavingsId(savingsAccountId)
                .withUseReference(multiTenantTransferDetails.getReference() + "");

        final CommandWrapper commandRequest = undoWithdrawBuilder
                .undoSavingsAccountTransactionWithReference(savingsAccountId, multiTenantTransferDetails.getReference(), null, "true")
                .build();
        this.commandsSourceWritePlatformService.logCommandSource(commandRequest);

        if (isSourceTenant) {

            MultiTenantTransferDetails newMultiTenantTransferDetails = multiTenantTransferRepository
                    .findByReference(multiTenantTransferDetails.getReference())
                    .orElseThrow(() -> new TransactionUndoNotAllowedException(0L, multiTenantTransferDetails.getReference()));
            newMultiTenantTransferDetails.setRolledBack(true);
            multiTenantTransferRepository.saveAndFlush(newMultiTenantTransferDetails);
        }
    }

    private void changeTenantDataContext(String tenantId) {
        FineractPlatformTenant tenant = tenantDetailsService.loadTenantById(tenantId);
        ThreadLocalContextUtil.setTenant(tenant);
        FineractContext context = ThreadLocalContextUtil.getContext();
        ThreadLocalContextUtil.init(context);
    }

    @Override
    @Transactional
    public void updateLoanTransaction(final Long loanTransactionId, final LoanTransaction newLoanTransaction) {
        final AccountTransferTransaction transferTransaction = this.accountTransferRepository.findByToLoanTransactionId(loanTransactionId);
        if (transferTransaction != null) {
            transferTransaction.updateToLoanTransaction(newLoanTransaction);
            this.accountTransferRepository.save(transferTransaction);
        }
    }

    private boolean isLoanToSavingsAccountTransfer(final PortfolioAccountType fromAccountType, final PortfolioAccountType toAccountType) {
        return fromAccountType.isLoanAccount() && toAccountType.isSavingsAccount();
    }

    private boolean isSavingsToLoanAccountTransfer(final PortfolioAccountType fromAccountType, final PortfolioAccountType toAccountType) {
        return fromAccountType.isSavingsAccount() && toAccountType.isLoanAccount();
    }

    private boolean isSavingsToSavingsAccountTransfer(final PortfolioAccountType fromAccountType,
                                                      final PortfolioAccountType toAccountType) {
        return fromAccountType.isSavingsAccount() && toAccountType.isSavingsAccount();
    }

    @Override
    @Transactional
    public CommandProcessingResult refundByTransfer(JsonCommand command) {
        // TODO Auto-generated method stub
        this.accountTransfersDataValidator.validate(command);

        final LocalDate transactionDate = command.localDateValueOfParameterNamed(transferDateParamName);
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed(transferAmountParamName);

        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(command.dateFormat()).withLocale(locale);

        final PaymentDetail paymentDetail = null;
        Long transferTransactionId = null;

        final Long fromLoanAccountId = command.longValueOfParameterNamed(fromAccountIdParamName);
        final Loan fromLoanAccount = this.loanAccountAssembler.assembleFrom(fromLoanAccountId);

        BigDecimal overpaid = this.loanReadPlatformService.retrieveTotalPaidInAdvance(fromLoanAccountId).getPaidInAdvance();
        final boolean backdatedTxnsAllowedTill = false;

        if (overpaid == null || overpaid.compareTo(BigDecimal.ZERO) == 0 || transactionAmount.floatValue() > overpaid.floatValue()) {
            if (overpaid == null) {
                overpaid = BigDecimal.ZERO;
            }
            throw new InvalidPaidInAdvanceAmountException(overpaid.toPlainString());
        }

        ExternalId externalId = externalIdFactory.create();

        final LoanTransaction loanRefundTransaction = this.loanAccountDomainService.makeRefundForActiveLoan(fromLoanAccountId,
                new CommandProcessingResultBuilder(), transactionDate, transactionAmount, paymentDetail, null, externalId);

        final Long toSavingsAccountId = command.longValueOfParameterNamed(toAccountIdParamName);
        final SavingsAccount toSavingsAccount = this.savingsAccountAssembler.assembleFrom(toSavingsAccountId, backdatedTxnsAllowedTill);

        final SavingsAccountTransaction deposit = this.savingsAccountDomainService.handleDeposit(toSavingsAccount, fmt, transactionDate,
                transactionAmount, paymentDetail, true, true, backdatedTxnsAllowedTill);

        final AccountTransferDetails accountTransferDetails = this.accountTransferAssembler.assembleLoanToSavingsTransfer(command,
                fromLoanAccount, toSavingsAccount, deposit, loanRefundTransaction);
        this.accountTransferDetailRepository.saveAndFlush(accountTransferDetails);
        transferTransactionId = accountTransferDetails.getId();

        final CommandProcessingResultBuilder builder = new CommandProcessingResultBuilder().withEntityId(transferTransactionId);

        // if (fromAccountType.isSavingsAccount()) {

        builder.withSavingsId(toSavingsAccountId);
        // }

        return builder.build();
    }
}
