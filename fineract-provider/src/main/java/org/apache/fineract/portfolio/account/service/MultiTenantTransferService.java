package org.apache.fineract.portfolio.account.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;

import java.text.ParseException;

public interface MultiTenantTransferService {
    CommandProcessingResult transferToAnotherTenant(final JsonCommand command);
}
