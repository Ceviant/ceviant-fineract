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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * The summary of an account loaded with only its latest transactions (SavingsAccountAssembler's limited fetch). As on
 * UAT: a savings account whose only transaction, a 500,000 deposit, was taken as the pivot balance and so not loaded,
 * then a 500,000 transfer out of it to activate a fixed deposit. Its summary became deposits 0, withdrawals 500,000,
 * balance -500,000.
 */
class SavingsAccountLimitedSummaryTest {

    private static final MonetaryCurrency NGN = new MonetaryCurrency("NGN", 2, 0);

    private MockedStatic<MoneyHelper> moneyHelper;
    private final Map<SavingsAccountTransaction, BigDecimal> deposits = new HashMap<>();
    private final Map<SavingsAccountTransaction, BigDecimal> withdrawals = new HashMap<>();
    private SavingsAccountTransactionSummaryWrapper wrapper;
    private SavingsAccount account;

    @BeforeEach
    void setUp() {
        moneyHelper = Mockito.mockStatic(MoneyHelper.class);
        moneyHelper.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
        moneyHelper.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));

        wrapper = mock(SavingsAccountTransactionSummaryWrapper.class);
        when(wrapper.calculateTotalDeposits(any(), anyList())).thenAnswer(invocation -> sum(invocation.getArgument(1), deposits));
        when(wrapper.calculateTotalWithdrawals(any(), anyList())).thenAnswer(invocation -> sum(invocation.getArgument(1), withdrawals));

        account = new SavingsAccount();
        account.currency = NGN;
        account.summary = new SavingsAccountSummary();
        account.setHelpers(wrapper, null);
    }

    @AfterEach
    void tearDown() {
        moneyHelper.close();
    }

    private static BigDecimal sum(final List<SavingsAccountTransaction> transactions,
            final Map<SavingsAccountTransaction, BigDecimal> amounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (final SavingsAccountTransaction transaction : transactions) {
            total = total.add(amounts.getOrDefault(transaction, BigDecimal.ZERO));
        }
        return total;
    }

    private SavingsAccountTransaction transaction(final Long id, final String amount, final Map<SavingsAccountTransaction, BigDecimal> kind) {
        final SavingsAccountTransaction transaction = mock(SavingsAccountTransaction.class);
        when(transaction.getId()).thenReturn(id);
        kind.put(transaction, new BigDecimal(amount));
        return transaction;
    }

    /** The stored summary of an account whose history is these transactions. */
    private void storedSummaryOf(final SavingsAccountTransaction... history) {
        account.summary.updateSummary(NGN, wrapper, new ArrayList<>(List.of(history)));
    }

    @Test
    void aTransferOutOfAnAccountLoadedWithoutItsHistoryKeepsItsTotals() {
        storedSummaryOf(transaction(37886L, "500000", deposits));
        // The deposit became the pivot balance, so no transaction was loaded.
        account.markLoadedWithLimitedTransactions();
        account.setTransactions(List.of(transaction(null, "500000", withdrawals)));

        account.updateSummaryFromTransactions();

        assertThat(account.summary.getTotalDeposits()).isEqualByComparingTo("500000");
        assertThat(account.summary.getTotalWithdrawals()).isEqualByComparingTo("500000");
        assertThat(account.summary.getAccountBalance()).isEqualByComparingTo("0");
    }

    @Test
    void aLoadedTransactionIsNotCountedTwice() {
        final SavingsAccountTransaction earlier = transaction(1L, "300000", deposits);
        final SavingsAccountTransaction latest = transaction(2L, "200000", deposits);
        storedSummaryOf(earlier, latest);
        // The earlier deposit is the pivot balance; the latest one is loaded.
        account.setTransactions(List.of(latest));
        account.markLoadedWithLimitedTransactions();
        account.setTransactions(List.of(transaction(null, "50000", withdrawals)));

        account.updateSummaryFromTransactions();

        assertThat(account.summary.getTotalDeposits()).isEqualByComparingTo("500000");
        assertThat(account.summary.getTotalWithdrawals()).isEqualByComparingTo("50000");
        assertThat(account.summary.getAccountBalance()).isEqualByComparingTo("450000");
    }

    @Test
    void updatingTwiceInTheSameUnitOfWorkCountsTheNewTransactionOnce() {
        storedSummaryOf(transaction(37886L, "500000", deposits));
        account.markLoadedWithLimitedTransactions();
        final SavingsAccountTransaction withdrawal = transaction(null, "500000", withdrawals);
        account.setTransactions(List.of(withdrawal));

        account.updateSummaryFromTransactions();
        // Saved in between, so it now has an id, then the summary is updated again.
        when(withdrawal.getId()).thenReturn(37887L);
        account.updateSummaryFromTransactions();

        assertThat(account.summary.getTotalWithdrawals()).isEqualByComparingTo("500000");
        assertThat(account.summary.getAccountBalance()).isEqualByComparingTo("0");
    }

    @Test
    void anAccountLoadedWithAllItsTransactionsIsSummedFromThem() {
        final SavingsAccountTransaction deposit = transaction(37886L, "500000", deposits);
        storedSummaryOf(deposit);
        account.setTransactions(List.of(deposit, transaction(null, "500000", withdrawals)));

        account.updateSummaryFromTransactions();

        assertThat(account.summary.getTotalDeposits()).isEqualByComparingTo("500000");
        assertThat(account.summary.getAccountBalance()).isEqualByComparingTo("0");
    }
}
