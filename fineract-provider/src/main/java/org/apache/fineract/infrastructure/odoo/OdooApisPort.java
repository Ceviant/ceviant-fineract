package org.apache.fineract.infrastructure.odoo;

import org.apache.fineract.infrastructure.odoo.invoker.ApiException;
import org.apache.fineract.infrastructure.odoo.model.LedgerAccount;
import org.apache.fineract.infrastructure.odoo.model.SuccessResponse;
import org.apache.fineract.infrastructure.odoo.model.TransactionRequest;

import java.util.List;

public interface OdooApisPort {

    SuccessResponse odooPostLedger1(TransactionRequest transactionRequest) throws ApiException;

    SuccessResponse odooPutLedger1(TransactionRequest transactionRequest) throws ApiException;

    List<LedgerAccount> odooGlAccounts1() throws ApiException;

    SuccessResponse odooPostGlAccounts1(LedgerAccount ledgerAccount) throws ApiException;

}
