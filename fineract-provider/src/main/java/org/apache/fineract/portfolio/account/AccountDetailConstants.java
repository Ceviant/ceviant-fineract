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
package org.apache.fineract.portfolio.account;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class AccountDetailConstants {

    private AccountDetailConstants() {

    }

    // general
    public static final String localeParamName = "locale";
    public static final String dateFormatParamName = "dateFormat";

    // savings product and account parameters
    public static final String idParamName = "id";
    public static final String fromOfficeIdParamName = "fromOfficeId";
    public static final String fromClientIdParamName = "fromClientId";
    public static final String fromAccountIdParamName = "fromAccountId";
    public static final String fromAccountTypeParamName = "fromAccountType";
    public static final String toOfficeIdParamName = "toOfficeId";
    public static final String toClientIdParamName = "toClientId";
    public static final String toAccountIdParamName = "toAccountId";
    public static final String toAccountTypeParamName = "toAccountType";
    public static final String transferTypeParamName = "transferType";
    public static final String toTenantIdParamName = "toTenantId";
    public static final String transferDateParamName = "transferDate";
    public static final String transferAmountParamName = "transferAmount";
    public static final String referenceParamName = "reference";
    public static final String transferDescriptionParamName = "transferDescription";

    public static final Set<String> REQUEST_DATA_PARAMETERS = new HashSet<>(Arrays.asList(fromOfficeIdParamName, fromClientIdParamName,
            fromAccountTypeParamName, fromAccountIdParamName, toTenantIdParamName, toOfficeIdParamName, toClientIdParamName,
            toAccountTypeParamName, toAccountIdParamName, dateFormatParamName, localeParamName, transferDateParamName,
            transferAmountParamName, referenceParamName, transferDescriptionParamName));
}
