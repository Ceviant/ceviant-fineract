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
package org.apache.fineract.commands.domain;

import java.io.Serial;
import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;
import org.apache.fineract.useradministration.api.PasswordPreferencesApiConstants;

@Getter
public class CommandWrapper implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long commandId;
    @SuppressWarnings("unused")
    private final Long officeId;
    private final Long groupId;
    private final Long clientId;
    private final Long loanId;
    private final Long savingsId;
    private final String actionName;
    private final String entityName;
    private final String taskPermissionName;
    private final Long entityId;
    private final Long subentityId;
    private final String href;
    private final String json;
    private final String transactionId;
    private final Long productId;
    private final Long creditBureauId;
    private final Long organisationCreditBureauId;
    private final String jobName;

    private final String idempotencyKey;

    @lombok.Getter
    private String transactionAmount;
    @lombok.Getter
    private String useRef;
    @lombok.Getter
    private String reference;

    @Setter
    private boolean isRequestAsync = false;

    @Setter
    private String operation;

    @SuppressWarnings("unused")
    private Long templateId;

    public static CommandWrapper wrap(final String actionName, final String entityName, final Long resourceId, final Long subresourceId) {
        return new CommandWrapper(null, actionName, entityName, resourceId, subresourceId, null, null, null, null, null, false);
    }

    public static CommandWrapper fromExistingCommand(final Long commandId, final String actionName, final String entityName,
            final Long resourceId, final Long subresourceId, final String resourceGetUrl, final Long productId) {
        return new CommandWrapper(commandId, actionName, entityName, resourceId, subresourceId, resourceGetUrl, productId, null, null, null,
                false);
    }

    public static CommandWrapper fromExistingCommand(final Long commandId, final String actionName, final String entityName,
            final Long resourceId, final Long subresourceId, final String resourceGetUrl, final Long productId, final Long officeId,
            final Long groupId, final Long clientId, final Long loanId, final Long savingsId, final String transactionId,
            final Long creditBureauId, final Long organisationCreditBureauId, final String idempotencyKey) {
        return new CommandWrapper(commandId, actionName, entityName, resourceId, subresourceId, resourceGetUrl, productId, officeId,
                groupId, clientId, loanId, savingsId, transactionId, creditBureauId, organisationCreditBureauId, idempotencyKey);
    }

    private CommandWrapper(final Long commandId, final String actionName, final String entityName, final Long resourceId,
            final Long subresourceId, final String resourceGetUrl, final Long productId, final String transactionAmount,
            final String useRef, final String reference, final boolean isRequestAsync) {
        this.commandId = commandId;
        this.officeId = null;
        this.groupId = null;
        this.clientId = null;
        this.loanId = null;
        this.savingsId = null;
        this.actionName = actionName;
        this.entityName = entityName;
        this.taskPermissionName = actionName + "_" + entityName;
        this.entityId = resourceId;
        this.subentityId = subresourceId;
        this.href = resourceGetUrl;
        this.json = null;
        this.transactionId = null;
        this.productId = productId;
        this.creditBureauId = null;
        this.organisationCreditBureauId = null;
        this.jobName = null;
        this.idempotencyKey = null;
        this.isRequestAsync = isRequestAsync;
        this.transactionAmount = transactionAmount;
        this.useRef = useRef;
        this.reference = reference;
    }

    public CommandWrapper(final Long officeId, final Long groupId, final Long clientId, final Long loanId, final Long savingsId,
            final String actionName, final String entityName, final Long entityId, final Long subentityId, final String href,
            final String json, final String transactionId, final Long productId, final Long templateId, final Long creditBureauId,
            final Long organisationCreditBureauId, final String jobName, final String idempotencyKey, final String transactionAmount,
            String useRef, final String reference) {

        this.commandId = null;
        this.officeId = officeId;
        this.groupId = groupId;
        this.clientId = clientId;
        this.loanId = loanId;
        this.savingsId = savingsId;
        this.actionName = actionName;
        this.entityName = entityName;
        this.taskPermissionName = actionName + "_" + entityName;
        this.entityId = entityId;
        this.subentityId = subentityId;
        this.href = href;
        this.json = json;
        this.transactionId = transactionId;
        this.productId = productId;
        this.templateId = templateId;
        this.creditBureauId = creditBureauId;
        this.organisationCreditBureauId = organisationCreditBureauId;
        this.jobName = jobName;
        this.idempotencyKey = idempotencyKey;
        this.transactionAmount = transactionAmount;
        this.useRef = useRef;
        this.reference = reference;
        this.isRequestAsync = false;
    }

    public CommandWrapper(final Long officeId, final Long groupId, final Long clientId, final Long loanId, final Long savingsId,
            final String actionName, final String entityName, final Long entityId, final Long subentityId, final String href,
            final String json, final String transactionId, final Long productId, final Long templateId, final Long creditBureauId,
            final Long organisationCreditBureauId, final String jobName, final String idempotencyKey, final String transactionAmount,
            String useRef, final String reference, final boolean isRequestAsync) {

        this.commandId = null;
        this.officeId = officeId;
        this.groupId = groupId;
        this.clientId = clientId;
        this.loanId = loanId;
        this.savingsId = savingsId;
        this.actionName = actionName;
        this.entityName = entityName;
        this.taskPermissionName = actionName + "_" + entityName;
        this.entityId = entityId;
        this.subentityId = subentityId;
        this.href = href;
        this.json = json;
        this.transactionId = transactionId;
        this.productId = productId;
        this.templateId = templateId;
        this.creditBureauId = creditBureauId;
        this.organisationCreditBureauId = organisationCreditBureauId;
        this.jobName = jobName;
        this.idempotencyKey = idempotencyKey;
        this.transactionAmount = transactionAmount;
        this.useRef = useRef;
        this.reference = reference;
        this.isRequestAsync = isRequestAsync;
    }

    private CommandWrapper(final Long commandId, final String actionName, final String entityName, final Long resourceId,
            final Long subresourceId, final String resourceGetUrl, final Long productId, final Long officeId, final Long groupId,
            final Long clientId, final Long loanId, final Long savingsId, final String transactionId, final Long creditBureauId,
            final Long organisationCreditBureauId, final String idempotencyKey) {

        this.commandId = commandId;
        this.officeId = officeId;
        this.groupId = groupId;
        this.clientId = clientId;
        this.loanId = loanId;
        this.savingsId = savingsId;
        this.actionName = actionName;
        this.entityName = entityName;
        this.taskPermissionName = actionName + "_" + entityName;
        this.entityId = resourceId;
        this.subentityId = subresourceId;
        this.href = resourceGetUrl;
        this.json = null;
        this.transactionId = transactionId;
        this.productId = productId;
        this.creditBureauId = creditBureauId;
        this.organisationCreditBureauId = organisationCreditBureauId;
        this.jobName = null;
        this.idempotencyKey = idempotencyKey;
    }

    public boolean isCreate() {
        return this.actionName.equalsIgnoreCase("CREATE");
    }

    public boolean isCreateDatatable() {
        return this.actionName.equalsIgnoreCase("CREATE") && this.href.startsWith("/datatables/") && this.entityId == null;
    }

    public boolean isDeleteDatatable() {
        return this.actionName.equalsIgnoreCase("DELETE") && this.href.startsWith("/datatables/") && this.entityId == null;
    }

    public boolean isUpdateDatatable() {
        return this.actionName.equalsIgnoreCase("UPDATE") && this.href.startsWith("/datatables/") && this.entityId == null;
    }

    public boolean isDatatableResource() {
        return this.href.startsWith("/datatables/");
    }

    public boolean isDeleteOneToOne() {
        /* also covers case of deleting all of a one to many */
        return isDatatableResource() && isDeleteOperation() && this.subentityId == null;
    }

    public boolean isDeleteMultiple() {
        return isDatatableResource() && isDeleteOperation() && this.subentityId != null;
    }

    public boolean isUpdateOneToOne() {
        return isDatatableResource() && isUpdateOperation() && this.subentityId == null;
    }

    public boolean isUpdateMultiple() {
        return isDatatableResource() && isUpdateOperation() && this.subentityId != null;
    }

    public boolean isRegisterDatatable() {
        return this.actionName.equalsIgnoreCase("REGISTER") && this.href.startsWith("/datatables/") && this.entityId == null;
    }

    public boolean isNoteResource() {
        boolean isnoteResource = false;
        if (this.entityName.equalsIgnoreCase("CLIENTNOTE") || this.entityName.equalsIgnoreCase("LOANNOTE")
                || this.entityName.equalsIgnoreCase("LOANTRANSACTIONNOTE") || this.entityName.equalsIgnoreCase("SAVINGNOTE")
                || this.entityName.equalsIgnoreCase("GROUPNOTE")) {
            isnoteResource = true;
        }
        return isnoteResource;
    }

    public boolean isUpdateOfOwnUserDetails(final Long loggedInUserId) {
        return isUserResource() && isUpdate() && loggedInUserId.equals(this.entityId);
    }

    public boolean isUpdate() {
        // permissions resource has special update which involves no resource.
        return (isPermissionResource() && isUpdateOperation()) || (isCurrencyResource() && isUpdateOperation())
                || (isCacheResource() && isUpdateOperation()) || (isWorkingDaysResource() && isUpdateOperation())
                || (isPasswordPreferencesResource() && isUpdateOperation()) || (isUpdateOperation() && (this.entityId != null));
    }

    public boolean isCacheResource() {
        return this.entityName.equalsIgnoreCase("CACHE");
    }

    public boolean isUpdateOperation() {
        return this.actionName.equalsIgnoreCase("UPDATE");
    }

    public boolean isDelete() {
        return isDeleteOperation() && this.entityId != null;
    }

    public boolean isDeleteOperation() {
        return this.actionName.equalsIgnoreCase("DELETE");
    }

    public boolean isSurveyResource() {
        return this.href.startsWith("/survey/");
    }

    public boolean isRegisterSurvey() {
        return this.actionName.equalsIgnoreCase("REGISTER");
    }

    public boolean isFullFilSurvey() {
        return this.actionName.equalsIgnoreCase("CREATE");
    }

    public boolean isWorkingDaysResource() {
        return this.entityName.equalsIgnoreCase("WORKINGDAYS");
    }

    public boolean isPasswordPreferencesResource() {
        return this.entityName.equalsIgnoreCase(PasswordPreferencesApiConstants.ENTITY_NAME);
    }

    public Long commandId() {
        return this.commandId;
    }

    public String actionName() {
        return this.actionName;
    }

    public String entityName() {
        return this.entityName;
    }

    public Long resourceId() {
        return this.entityId;
    }

    public Long subresourceId() {
        return this.subentityId;
    }

    public String taskPermissionName() {
        return this.actionName + "_" + this.entityName;
    }

    public boolean isPermissionResource() {
        return this.entityName.equalsIgnoreCase("PERMISSION");
    }

    public boolean isUserResource() {
        return this.entityName.equalsIgnoreCase("USER");
    }

    public boolean isCurrencyResource() {
        return this.entityName.equalsIgnoreCase("CURRENCY");
    }

    public String commandName() {
        return this.actionName + "_" + this.entityName;
    }

    public boolean isLoanDisburseDetailResource() {
        return this.entityName.equalsIgnoreCase("DISBURSEMENTDETAIL");
    }

    public boolean isUpdateDisbursementDate() {
        return this.actionName.equalsIgnoreCase("UPDATE") && this.entityName.equalsIgnoreCase("DISBURSEMENTDETAIL")
                && this.entityId != null;
    }

    public boolean addAndDeleteDisbursementDetails() {
        return this.actionName.equalsIgnoreCase("UPDATE") && this.entityName.equalsIgnoreCase("DISBURSEMENTDETAIL")
                && this.entityId == null;
    }
}
