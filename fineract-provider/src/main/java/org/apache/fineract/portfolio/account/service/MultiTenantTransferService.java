package org.apache.fineract.portfolio.account.service;

import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;

public interface MultiTenantTransferService {

    CommandProcessingResult transferToAnotherTenant(final JsonCommand command);
}
