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
package org.apache.fineract.infrastructure.core.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.springframework.util.Assert;

/**
 *
 */
public final class ThreadLocalContextUtil {

    public static final String CONTEXT_TENANTS = "tenants";
    private static final ThreadLocal<String> contextHolder = new ThreadLocal<>();
    private static final ThreadLocal<FineractPlatformTenant> tenantContext = new ThreadLocal<>();
    private static final ThreadLocal<String> authTokenContext = new ThreadLocal<>();
    private static final ThreadLocal<HashMap<BusinessDateType, LocalDate>> businessDateContext = new ThreadLocal<>();
    private static final ThreadLocal<ActionContext> actionContext = new ThreadLocal<>();
    private static final ThreadLocal<String> userAgent = new ThreadLocal<>();
    private static final ThreadLocal<String> ipAddress = new ThreadLocal<>();

    private ThreadLocalContextUtil() {}

    public static FineractPlatformTenant getTenant() {
        return tenantContext.get();
    }

    public static void setTenant(final FineractPlatformTenant tenant) {
        tenantContext.set(tenant);
    }

    public static void clearTenant() {
        tenantContext.remove();
    }

    public static String getDataSourceContext() {
        return contextHolder.get();
    }

    public static void setDataSourceContext(final String dataSourceContext) {
        contextHolder.set(dataSourceContext);
    }

    public static void clearDataSourceContext() {
        contextHolder.remove();
    }

    public static String getAuthToken() {
        return authTokenContext.get();
    }

    public static void setAuthToken(final String authToken) {
        authTokenContext.set(authToken);
    }

    // Map is not serializable, but Hashmap is
    public static HashMap<BusinessDateType, LocalDate> getBusinessDates() {
        Assert.notNull(businessDateContext.get(), "Business dates cannot be null!");
        return businessDateContext.get();
    }

    public static void setBusinessDates(HashMap<BusinessDateType, LocalDate> dates) {
        Assert.notNull(dates, "Business dates cannot be null!");
        businessDateContext.set(dates);
    }

    public static LocalDate getBusinessDateByType(BusinessDateType businessDateType) {
        Assert.notNull(businessDateType, "Business date type cannot be null!");
        LocalDate localDate = getBusinessDates().get(businessDateType);
        Assert.notNull(localDate, String.format("Business date with type `%s` is not initialised!", businessDateType));
        return localDate;
    }

    public static LocalDate getBusinessDate() {
        BusinessDateType businessDateType = getActionContext().getBusinessDateType();
        return getBusinessDateByType(businessDateType);
    }

    public static ActionContext getActionContext() {
        return actionContext.get() == null ? ActionContext.DEFAULT : actionContext.get();
    }

    public static void setActionContext(ActionContext context) {
        Assert.notNull(context, "context cannot be null");
        actionContext.set(context);
    }

    public static String getClientIpAddr() {
        return ipAddress.get();
    }

    public static void setIpAddress(final String ipAddress) {
        ThreadLocalContextUtil.ipAddress.set(ipAddress);
    }

    public static String getClientUserAgent() {
        return userAgent.get();
    }

    public static void setUserAgent(final String userAgent) {
        ThreadLocalContextUtil.userAgent.set(userAgent);
    }

    public static FineractContext getContext() {
        return new FineractContext(getDataSourceContext(), getTenant(), getAuthToken(), getBusinessDates(), getActionContext());
    }

    public static void init(final FineractContext fineractContext) {
        Assert.notNull(fineractContext, "FineractContext cannot be null during synchronisation!");
        setDataSourceContext(fineractContext.getContextHolder());
        setTenant(fineractContext.getTenantContext());
        setAuthToken(fineractContext.getAuthTokenContext());
        setBusinessDates(fineractContext.getBusinessDateContext());
        setActionContext(fineractContext.getActionContext());
    }

    public static void reset() {
        contextHolder.remove();
        tenantContext.remove();
        authTokenContext.remove();
        businessDateContext.remove();
        actionContext.remove();
        ipAddress.remove();
        userAgent.remove();
    }

    public static void restoreThreadContext(Map<String, Object> threadContextCopy) {
        ThreadLocalContextUtil.reset();

        if (threadContextCopy.containsKey("tenant")) {
            ThreadLocalContextUtil.setTenant((FineractPlatformTenant) threadContextCopy.get("tenant"));
        }

        if (threadContextCopy.containsKey("authToken")) {
            ThreadLocalContextUtil.setAuthToken((String) threadContextCopy.get("authToken"));
        }

        if (threadContextCopy.containsKey("businessDate")) {
            try {
                HashMap<BusinessDateType, LocalDate> dates = (HashMap<BusinessDateType, LocalDate>) threadContextCopy.get("businessDate");
                ThreadLocalContextUtil.setBusinessDates(dates);
            } catch (Exception e) {
                throw new PlatformDataIntegrityException("async.camel.businessdate.deserialize.error",
                        "Failed to deserialize business dates");
            }
        }

        if (threadContextCopy.containsKey("actionContext")) {
            ThreadLocalContextUtil.setActionContext(ActionContext.valueOf((String) threadContextCopy.get("actionContext")));
        }
    }
}
