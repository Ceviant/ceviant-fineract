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
package org.apache.fineract.portfolio.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * The balance check when SavingsAccountAssembler loaded only an account's latest transactions, with the balance before
 * them as its pivot balance. As on UAT for account 0000036285: one ₦500,000 deposit (taken as the pivot balance) and
 * the ₦50,000 transfer activating a fixed deposit from it.
 */
class SavingsAccountBalanceCheckTest {

    private static final MonetaryCurrency NGN = new MonetaryCurrency("NGN", 2, 0);

    private MockedStatic<MoneyHelper> moneyHelper;
    private SavingsAccount account;

    @BeforeEach
    void setUp() {
        moneyHelper = Mockito.mockStatic(MoneyHelper.class);
        moneyHelper.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
        moneyHelper.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));

        account = new SavingsAccount();
        account.currency = NGN;
        account.summary = new SavingsAccountSummary();
    }

    @AfterEach
    void tearDown() {
        moneyHelper.close();
    }

    private void loadedWithPivotBalance(final String balance, final SavingsAccountTransaction... transactions) {
        account.summary.setRunningBalanceOnPivotDate(new BigDecimal(balance));
        account.setTransactions(new ArrayList<>(List.of(transactions)));
    }

    private static SavingsAccountTransaction debit(final String amount) {
        // Built before stubbing: Money.of reads MoneyHelper, a mock itself, which Mockito refuses mid-stubbing.
        final Money money = Money.of(NGN, new BigDecimal(amount));
        final SavingsAccountTransaction transaction = mock(SavingsAccountTransaction.class);
        when(transaction.isNotReversed()).thenReturn(true);
        when(transaction.isCredit()).thenReturn(false);
        when(transaction.isDebit()).thenReturn(true);
        when(transaction.isReversalTransaction()).thenReturn(false);
        when(transaction.canProcessBalanceCheck()).thenReturn(true);
        when(transaction.getTransactionDate()).thenReturn(LocalDate.of(2026, 10, 19));
        when(transaction.getAmount(any(MonetaryCurrency.class))).thenReturn(money);
        return transaction;
    }

    @Test
    void aFundedAccountCanBeDebitedWhenOnlyItsLatestTransactionsAreLoaded() {
        loadedWithPivotBalance("500000", debit("50000"));

        assertThatCode(() -> account.validateAccountBalanceDoesNotBecomeNegative(new BigDecimal("50000"), false, null, false))
                .doesNotThrowAnyException();
    }

    @Test
    void theWholeBalanceCanBeDebited() {
        loadedWithPivotBalance("500000", debit("500000"));

        assertThatCode(() -> account.validateAccountBalanceDoesNotBecomeNegative(new BigDecimal("500000"), false, null, false))
                .doesNotThrowAnyException();
    }

    @Test
    void moreThanTheBalanceIsStillRefused() {
        loadedWithPivotBalance("500000", debit("500000.01"));

        assertThatThrownBy(() -> account.validateAccountBalanceDoesNotBecomeNegative(new BigDecimal("500000.01"), false, null, false))
                .isInstanceOf(InsufficientAccountBalanceException.class);
    }

    @Test
    void anAccountLoadedWithAllItsTransactionsStartsFromZero() {
        assertThat(account.openingBalanceForBalanceCheck(false).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        loadedWithPivotBalance("0", debit("1"));
        assertThatThrownBy(() -> account.validateAccountBalanceDoesNotBecomeNegative(new BigDecimal("1"), false, null, false))
                .isInstanceOf(InsufficientAccountBalanceException.class);
    }

    @Test
    void withBackdatedTransactionsThePivotBalanceIsUsedAsBefore() {
        account.summary.setRunningBalanceOnPivotDate(new BigDecimal("1200"));

        assertThat(account.openingBalanceForBalanceCheck(true).getAmount()).isEqualByComparingTo("1200");
    }
}
