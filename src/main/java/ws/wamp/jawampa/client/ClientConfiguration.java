/*
 * Copyright 2014 Matthias Einwag
 *
 * The jawampa authors license this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package ws.wamp.jawampa.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ws.wamp.jawampa.WampRoles;
import ws.wamp.jawampa.auth.client.ClientSideAuthentication;
import ws.wamp.jawampa.connection.IWampConnector;
import ws.wamp.jawampa.connection.IWampConnectorProvider;
import ws.wamp.jawampa.internal.Version;

/**
 * Stores various configuration data for WAMP clients
 */
public class ClientConfiguration {
    private final boolean closeClientOnErrors;
    
    private final String authId;
    private final List<ClientSideAuthentication> authMethods;
    
    private final ObjectMapper objectMapper;

    private final URI routerUri;
    private final String realm;
    private final boolean useStrictUriValidation;
    
    private final WampRoles[] clientRoles;
    
    private final int totalNrReconnects;
    private final int reconnectInterval;
    
    /** The provider that should be used to obtain a connector */
    private final IWampConnectorProvider connectorProvider;
    /** The connector which is used to create new connections to the remote peer */
    private final IWampConnector connector;

    private final ObjectNode helloDetails;
    
    public ClientConfiguration(
        boolean closeClientOnErrors,
        String authId,
        List<ClientSideAuthentication> authMethods,
        URI routerUri,
        String realm,
        boolean useStrictUriValidation,
        WampRoles[] clientRoles,
        int totalNrReconnects,
        int reconnectInterval,
        IWampConnectorProvider connectorProvider,
        IWampConnector connector, ObjectMapper objectMapper)
    {
        this.closeClientOnErrors = closeClientOnErrors;
        
        this.authId = authId;
        this.authMethods = authMethods;
        
        this.routerUri = routerUri;
        this.realm = realm;
        
        this.useStrictUriValidation = useStrictUriValidation;
        
        this.clientRoles = clientRoles;
        
        this.totalNrReconnects = totalNrReconnects;
        this.reconnectInterval = reconnectInterval;
        
        this.connectorProvider = connectorProvider;
        this.connector = connector;
        this.objectMapper = objectMapper;

        // Put the requested roles in the Hello message
        this.helloDetails = this.objectMapper.createObjectNode();
        this.helloDetails.put("agent", Version.getVersion());

        ObjectNode rolesNode = this.helloDetails.putObject("roles");
        for (WampRoles role : clientRoles) {
            ObjectNode roleNode = rolesNode.putObject(role.toString());
            if (role == WampRoles.Publisher ) {
                ObjectNode featuresNode = roleNode.putObject("features");
                featuresNode.put("publisher_exclusion", true);
            } else if (role == WampRoles.Subscriber) {
                ObjectNode featuresNode = roleNode.putObject("features");
                featuresNode.put("pattern_based_subscription", true);
            } else if (role == WampRoles.Caller) {
                ObjectNode featuresNode = roleNode.putObject("features");
                featuresNode.put("caller_identification", true);
            }
        }

        // Insert authentication data
        if(authId != null) {
            this.helloDetails.put("authid", authId);
        }
        if (authMethods != null && authMethods.size() != 0) {
            ArrayNode authMethodsNode = this.helloDetails.putArray("authmethods");
            for(ClientSideAuthentication authMethod : authMethods) {
                authMethodsNode.add(authMethod.getAuthMethod());
            }
        }
    }
    
    public boolean closeClientOnErrors() {
        return this.closeClientOnErrors;
    }
    
    public ObjectMapper objectMapper() {
        return this.objectMapper;
    }
    
    public URI routerUri() {
        return this.routerUri;
    }
    
    public String realm() {
        return this.realm;
    }
    
    public boolean useStrictUriValidation() {
        return this.useStrictUriValidation;
    }
    
    public int totalNrReconnects() {
        return this.totalNrReconnects;
    }
    
    public int reconnectInterval() {
        return this.reconnectInterval;
    }
    
    /** The connector which is used to create new connections to the remote peer */
    public IWampConnector connector() {
        return this.connector;
    }

    public WampRoles[] clientRoles() {
        return this.clientRoles.clone();
    }
    
    public String authId() {
        return this.authId;
    }
    
    public List<ClientSideAuthentication> authMethods() {
        return new ArrayList<>(this.authMethods);
    }

    public IWampConnectorProvider connectorProvider() {
        return this.connectorProvider;
    }

    ObjectNode helloDetails(){
        return this.helloDetails;
    }
}
