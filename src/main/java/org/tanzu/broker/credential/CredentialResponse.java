package org.tanzu.broker.credential;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ResourceAccessToken.class, name = "resource_access_token"),
    @JsonSubTypes.Type(value = UserDelegationRequired.class, name = "user_delegation_required")
})
public sealed interface CredentialResponse
    permits ResourceAccessToken, UserDelegationRequired {}
