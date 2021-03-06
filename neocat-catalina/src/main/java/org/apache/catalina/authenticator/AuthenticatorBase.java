/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.authenticator;

import java.io.IOException;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.AuthStatus;
import javax.security.auth.message.MessageInfo;
import javax.security.auth.message.config.AuthConfigFactory;
import javax.security.auth.message.config.AuthConfigProvider;
import javax.security.auth.message.config.RegistrationListener;
import javax.security.auth.message.config.ServerAuthConfig;
import javax.security.auth.message.config.ServerAuthContext;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.Authenticator;
import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Manager;
import org.apache.catalina.Realm;
import org.apache.catalina.Session;
import org.apache.catalina.TomcatPrincipal;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.realm.GenericPrincipal;
import org.apache.catalina.util.SessionIdGeneratorBase;
import org.apache.catalina.util.StandardSessionIdGenerator;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.res.StringManager;


/**
 * Basic implementation of the <b>Valve</b> interface that enforces the
 * <code>&lt;security-constraint&gt;</code> elements in the web application
 * deployment descriptor. This functionality is implemented as a Valve so that
 * it can be omitted in environments that do not require these features.
 * Individual implementations of each supported authentication method can
 * subclass this base class as required.
 * <p>
 * <b>USAGE CONSTRAINT</b>: When this class is utilized, the Context to which it
 * is attached (or a parent Container in a hierarchy) must have an associated
 * Realm that can be used for authenticating users and enumerating the roles to
 * which they have been assigned.
 * <p>
 * <b>USAGE CONSTRAINT</b>: This Valve is only useful when processing HTTP
 * requests. Requests of any other type will simply be passed through.
 *
 * @author Craig R. McClanahan
 */
public abstract class AuthenticatorBase extends ValveBase
        implements Authenticator, RegistrationListener {

    private final Logger log = LoggerFactory.getLogger(AuthenticatorBase.class); // must not be static

    /**
     * "Expires" header always set to Date(1), so generate once only
     */
    private static final String DATE_ONE = FastHttpDateFormat.formatDate(1);

    /**
     * The string manager for this package.
     */
    protected static final StringManager sm = StringManager.getManager(AuthenticatorBase.class);

    /**
     * Authentication header
     */
    protected static final String AUTH_HEADER_NAME = "WWW-Authenticate";

    /**
     * Default authentication realm name.
     */
    protected static final String REALM_NAME = "Authentication required";

    protected static String getRealmName(Context context) {
        if (context == null) {
            // Very unlikely
            return REALM_NAME;
        }

        LoginConfig config = context.getLoginConfig();
        if (config == null) {
            return REALM_NAME;
        }

        String result = config.getRealmName();
        if (result == null) {
            return REALM_NAME;
        }

        return result;
    }

    // ------------------------------------------------------ Constructor

    public AuthenticatorBase() {
        super(true);
    }

    // ----------------------------------------------------- Instance Variables

    /**
     * Should a session always be used once a user is authenticated? This may
     * offer some performance benefits since the session can then be used to
     * cache the authenticated Principal, hence removing the need to
     * authenticate the user via the Realm on every request. This may be of help
     * for combinations such as BASIC authentication used with the JNDIRealm or
     * DataSourceRealms. However there will also be the performance cost of
     * creating and GC'ing the session. By default, a session will not be
     * created.
     */
    protected boolean alwaysUseSession = false;

    /**
     * Should we cache authenticated Principals if the request is part of an
     * HTTP session?
     */
    protected boolean cache = true;

    /**
     * Should the session ID, if any, be changed upon a successful
     * authentication to prevent a session fixation attack?
     */
    protected boolean changeSessionIdOnAuthentication = true;

    /**
     * The Context to which this Valve is attached.
     */
    protected Context context = null;

    /**
     * Flag to determine if we disable proxy caching, or leave the issue up to
     * the webapp developer.
     */
    protected boolean disableProxyCaching = true;

    /**
     * Flag to determine if we disable proxy caching with headers incompatible
     * with IE.
     */
    protected boolean securePagesWithPragma = false;

    /**
     * The Java class name of the secure random number generator class to be
     * used when generating SSO session identifiers. The random number generator
     * class must be self-seeding and have a zero-argument constructor. If not
     * specified, an instance of {@link java.security.SecureRandom} will be
     * generated.
     */
    protected String secureRandomClass = null;

    /**
     * The name of the algorithm to use to create instances of
     * {@link java.security.SecureRandom} which are used to generate SSO session
     * IDs. If no algorithm is specified, SHA1PRNG is used. To use the platform
     * default (which may be SHA1PRNG), specify the empty string. If an invalid
     * algorithm and/or provider is specified the SecureRandom instances will be
     * created using the defaults. If that fails, the SecureRandom instances
     * will be created using platform defaults.
     */
    protected String secureRandomAlgorithm = "SHA1PRNG";

    /**
     * The name of the provider to use to create instances of
     * {@link java.security.SecureRandom} which are used to generate session SSO
     * IDs. If no algorithm is specified the of SHA1PRNG default is used. If an
     * invalid algorithm and/or provider is specified the SecureRandom instances
     * will be created using the defaults. If that fails, the SecureRandom
     * instances will be created using platform defaults.
     */
    protected String secureRandomProvider = null;

    protected SessionIdGeneratorBase sessionIdGenerator = null;

    private volatile String jaspicAppContextID = null;
    private volatile Optional<AuthConfigProvider> jaspicProvider = null;


    // ------------------------------------------------------------- Properties

    public boolean getAlwaysUseSession() {
        return alwaysUseSession;
    }

    public void setAlwaysUseSession(boolean alwaysUseSession) {
        this.alwaysUseSession = alwaysUseSession;
    }

    /**
     * Return the cache authenticated Principals flag.
     *
     * @return <code>true</code> if authenticated Principals will be cached,
     *         otherwise <code>false</code>
     */
    public boolean getCache() {
        return this.cache;
    }

    /**
     * Set the cache authenticated Principals flag.
     *
     * @param cache
     *            The new cache flag
     */
    public void setCache(boolean cache) {
        this.cache = cache;
    }

    /**
     * Return the Container to which this Valve is attached.
     */
    @Override
    public Container getContainer() {
        return this.context;
    }

    /**
     * Set the Container to which this Valve is attached.
     *
     * @param container
     *            The container to which we are attached
     */
    @Override
    public void setContainer(Container container) {

        if (container != null && !(container instanceof Context)) {
            throw new IllegalArgumentException(sm.getString("authenticator.notContext"));
        }

        super.setContainer(container);
        this.context = (Context) container;

    }

    /**
     * Return the flag that states if we add headers to disable caching by
     * proxies.
     *
     * @return <code>true</code> if the headers will be added, otherwise
     *         <code>false</code>
     */
    public boolean getDisableProxyCaching() {
        return disableProxyCaching;
    }

    /**
     * Set the value of the flag that states if we add headers to disable
     * caching by proxies.
     *
     * @param nocache
     *            <code>true</code> if we add headers to disable proxy caching,
     *            <code>false</code> if we leave the headers alone.
     */
    public void setDisableProxyCaching(boolean nocache) {
        disableProxyCaching = nocache;
    }

    /**
     * Return the flag that states, if proxy caching is disabled, what headers
     * we add to disable the caching.
     *
     * @return <code>true</code> if a Pragma header should be used, otherwise
     *         <code>false</code>
     */
    public boolean getSecurePagesWithPragma() {
        return securePagesWithPragma;
    }

    /**
     * Set the value of the flag that states what headers we add to disable
     * proxy caching.
     *
     * @param securePagesWithPragma
     *            <code>true</code> if we add headers which are incompatible
     *            with downloading office documents in IE under SSL but which
     *            fix a caching problem in Mozilla.
     */
    public void setSecurePagesWithPragma(boolean securePagesWithPragma) {
        this.securePagesWithPragma = securePagesWithPragma;
    }

    /**
     * Return the flag that states if we should change the session ID of an
     * existing session upon successful authentication.
     *
     * @return <code>true</code> to change session ID upon successful
     *         authentication, <code>false</code> to do not perform the change.
     */
    public boolean getChangeSessionIdOnAuthentication() {
        return changeSessionIdOnAuthentication;
    }

    /**
     * Set the value of the flag that states if we should change the session ID
     * of an existing session upon successful authentication.
     *
     * @param changeSessionIdOnAuthentication <code>true</code> to change
     *            session ID upon successful authentication, <code>false</code>
     *            to do not perform the change.
     */
    public void setChangeSessionIdOnAuthentication(boolean changeSessionIdOnAuthentication) {
        this.changeSessionIdOnAuthentication = changeSessionIdOnAuthentication;
    }

    /**
     * Return the secure random number generator class name.
     *
     * @return The fully qualified name of the SecureRandom implementation to
     *         use
     */
    public String getSecureRandomClass() {
        return this.secureRandomClass;
    }

    /**
     * Set the secure random number generator class name.
     *
     * @param secureRandomClass
     *            The new secure random number generator class name
     */
    public void setSecureRandomClass(String secureRandomClass) {
        this.secureRandomClass = secureRandomClass;
    }

    /**
     * Return the secure random number generator algorithm name.
     *
     * @return The name of the SecureRandom algorithm used
     */
    public String getSecureRandomAlgorithm() {
        return secureRandomAlgorithm;
    }

    /**
     * Set the secure random number generator algorithm name.
     *
     * @param secureRandomAlgorithm
     *            The new secure random number generator algorithm name
     */
    public void setSecureRandomAlgorithm(String secureRandomAlgorithm) {
        this.secureRandomAlgorithm = secureRandomAlgorithm;
    }

    /**
     * Return the secure random number generator provider name.
     *
     * @return The name of the SecureRandom provider
     */
    public String getSecureRandomProvider() {
        return secureRandomProvider;
    }

    /**
     * Set the secure random number generator provider name.
     *
     * @param secureRandomProvider
     *            The new secure random number generator provider name
     */
    public void setSecureRandomProvider(String secureRandomProvider) {
        this.secureRandomProvider = secureRandomProvider;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Enforce the security restrictions in the web application deployment
     * descriptor of our associated Context.
     *
     * @param request
     *            Request to be processed
     * @param response
     *            Response to be processed
     *
     * @exception IOException
     *                if an input/output error occurs
     * @exception ServletException
     *                if thrown by a processing element
     */
    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {

        if (log.isDebugEnabled()) {
            log.debug("Security checking request " + request.getMethod() + " " +
                    request.getRequestURI());
        }

        // Have we got a cached authenticated Principal to record?
        if (cache) {
            Principal principal = request.getUserPrincipal();
            if (principal == null) {
                Session session = request.getSessionInternal(false);
                if (session != null) {
                    principal = session.getPrincipal();
                    if (principal != null) {
                        if (log.isDebugEnabled()) {
                            log.debug("We have cached auth type " + session.getAuthType() +
                                    " for principal " + principal);
                        }
                        request.setAuthType(session.getAuthType());
                        request.setUserPrincipal(principal);
                    }
                }
            }
        }

        boolean authRequired = isContinuationRequired(request);

        Realm realm = this.context.getRealm();
        // Is this request URI subject to a security constraint?
        SecurityConstraint[] constraints = realm.findSecurityConstraints(request, this.context);

        authRequired = false;

        if (constraints == null && !context.getPreemptiveAuthentication() && !authRequired) {
            if (log.isDebugEnabled()) {
                log.debug(" Not subject to any constraint");
            }
            getNext().invoke(request, response);
            return;
        }

        // Make sure that constrained resources are not cached by web proxies
        // or browsers as caching can provide a security hole
        if (constraints != null && disableProxyCaching &&
                !"POST".equalsIgnoreCase(request.getMethod())) {
            if (securePagesWithPragma) {
                // Note: These can cause problems with downloading files with IE
                response.setHeader("Pragma", "No-cache");
                response.setHeader("Cache-Control", "no-cache");
            } else {
                response.setHeader("Cache-Control", "private");
            }
            response.setHeader("Expires", DATE_ONE);
        }

        if (constraints != null) {
            // Enforce any user data constraint for this security constraint
            if (log.isDebugEnabled()) {
                log.debug(" Calling hasUserDataPermission()");
            }
            if (!realm.hasUserDataPermission(request, response, constraints)) {
                if (log.isDebugEnabled()) {
                    log.debug(" Failed hasUserDataPermission() test");
                }
                /*
                 * ASSERT: Authenticator already set the appropriate HTTP status
                 * code, so we do not have to do anything special
                 */
                return;
            }
        }

        // Since authenticate modifies the response on failure,
        // we have to check for allow-from-all first.
        boolean hasAuthConstraint = false;
        if (constraints != null) {
            hasAuthConstraint = true;
            for (int i = 0; i < constraints.length && hasAuthConstraint; i++) {
                if (!constraints[i].getAuthConstraint()) {
                    hasAuthConstraint = false;
                } else if (!constraints[i].getAllRoles() &&
                        !constraints[i].getAuthenticatedUsers()) {
                    String[] roles = constraints[i].findAuthRoles();
                    if (roles == null || roles.length == 0) {
                        hasAuthConstraint = false;
                    }
                }
            }
        }

        if (!authRequired && hasAuthConstraint) {
            authRequired = true;
        }

        if (!authRequired && context.getPreemptiveAuthentication()) {
            authRequired =
                    request.getCoyoteRequest().getMimeHeaders().getValue("authorization") != null;
        }

        if (authRequired) {
            if (log.isDebugEnabled()) {
                log.debug(" Calling authenticate()");
            }
            
            if (!doAuthenticate(request, response)) {
                if (log.isDebugEnabled()) {
                    log.debug(" Failed authenticate() test");
                }
                /*
                 * ASSERT: Authenticator already set the appropriate HTTP status
                 * code, so we do not have to do anything special
                 */
                return;
            }
        }

        if (constraints != null) {
            if (log.isDebugEnabled()) {
                log.debug(" Calling accessControl()");
            }
            if (!realm.hasResourcePermission(request, response, constraints, this.context)) {
                if (log.isDebugEnabled()) {
                    log.debug(" Failed accessControl() test");
                }
                /*
                 * ASSERT: AccessControl method has already set the appropriate
                 * HTTP status code, so we do not have to do anything special
                 */
                return;
            }
        }

        // Any and all specified constraints have been satisfied
        if (log.isDebugEnabled()) {
            log.debug(" Successfully passed all security constraints");
        }
        getNext().invoke(request, response);
    }


    @Override
    public boolean authenticate(Request request, HttpServletResponse httpResponse)
            throws IOException {
    	return doAuthenticate(request, httpResponse);
    }

    
    // ------------------------------------------------------ Protected Methods

    /**
     * Provided for sub-classes to implement their specific authentication
     * mechanism.
     *
     * @param request The request that triggered the authentication
     * @param response The response associated with the request
     *
     * @return {@code true} if the the user was authenticated, otherwise {@code
     *         false}, in which case an authentication challenge will have been
     *         written to the response
     *
     * @throws IOException If an I/O problem occurred during the authentication
     *                     process
     */
    protected abstract boolean doAuthenticate(Request request, HttpServletResponse response)
            throws IOException;


    /**
     * Does this authenticator require that {@link #authenticate(Request,
     * HttpServletResponse)} is called to continue an authentication process
     * that started in a previous request?
     *
     * @param request The request currently being processed
     *
     * @return {@code true} if authenticate() must be called, otherwise
     *         {@code false}
     */
    protected boolean isContinuationRequired(Request request) {
        return false;
    }


    /**
     * Check to see if the user has already been authenticated earlier in the
     * processing chain or if there is enough information available to
     * authenticate the user without requiring further user interaction.
     *
     * @param request
     *            The current request
     * @param response
     *            The current response
     * @param useSSO
     *            Should information available from SSO be used to attempt to
     *            authenticate the current user?
     *
     * @return <code>true</code> if the user was authenticated via the cache,
     *         otherwise <code>false</code>
     */
    protected boolean checkForCachedAuthentication(Request request, HttpServletResponse response) {

        // Has the user already been authenticated?
        Principal principal = request.getUserPrincipal();
        if (principal != null) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("authenticator.check.found", principal.getName()));
            }
            // Associate the session with any existing SSO session. Even if
            // useSSO is false, this will ensure coordinated session
            // invalidation at log out.
            return true;
        }

        // Has the Connector provided a pre-authenticated Principal that now
        // needs to be authorized?
        if (request.getCoyoteRequest().getRemoteUserNeedsAuthorization()) {
            String username = request.getCoyoteRequest().getRemoteUser().toString();
            if (username != null) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("authenticator.check.authorize", username));
                }
                Principal authorized = context.getRealm().authenticate(username);
                if (authorized == null) {
                    // Realm doesn't recognise user. Create a user with no roles
                    // from the authenticated user name
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("authenticator.check.authorizeFail", username));
                    }
                    authorized = new GenericPrincipal(username, null, null);
                }
                String authType = request.getAuthType();
                if (authType == null || authType.length() == 0) {
                    authType = getAuthMethod();
                }
                register(request, response, authorized, authType, username, null);
                return true;
            }
        }
        return false;
    }


    /**
     * Register an authenticated Principal and authentication type in our
     * request, in the current session (if there is one), and with our
     * SingleSignOn valve, if there is one. Set the appropriate cookie to be
     * returned.
     *
     * @param request
     *            The servlet request we are processing
     * @param response
     *            The servlet response we are generating
     * @param principal
     *            The authenticated Principal to be registered
     * @param authType
     *            The authentication type to be registered
     * @param username
     *            Username used to authenticate (if any)
     * @param password
     *            Password used to authenticate (if any)
     */
    public void register(Request request, HttpServletResponse response, Principal principal,
            String authType, String username, String password) {
        register(request, response, principal, authType, username, password, alwaysUseSession, cache);
    }


    private void register(Request request, HttpServletResponse response, Principal principal,
            String authType, String username, String password, boolean alwaysUseSession,
            boolean cache) {

        if (log.isDebugEnabled()) {
            String name = (principal == null) ? "none" : principal.getName();
            log.debug("Authenticated '" + name + "' with type '" + authType + "'");
        }

        // Cache the authentication information in our request
        request.setAuthType(authType);
        request.setUserPrincipal(principal);

        Session session = request.getSessionInternal(false);

        if (session != null) {
            // If the principal is null then this is a logout. No need to change
            // the session ID. See BZ 59043.
            if (changeSessionIdOnAuthentication && principal != null) {
                String oldId = null;
                if (log.isDebugEnabled()) {
                    oldId = session.getId();
                }
                Manager manager = request.getContext().getManager();
                manager.changeSessionId(session);
                request.changeSessionId(session.getId());
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("authenticator.changeSessionId",
                            oldId, session.getId()));
                }
            }
        } else if (alwaysUseSession) {
            session = request.getSessionInternal(true);
        }

        // Cache the authentication information in our session, if any
        if (cache) {
            if (session != null) {
                session.setAuthType(authType);
                session.setPrincipal(principal);
                if (username != null) {
                    session.setNote(Constants.SESS_USERNAME_NOTE, username);
                } else {
                    session.removeNote(Constants.SESS_USERNAME_NOTE);
                }
                if (password != null) {
                    session.setNote(Constants.SESS_PASSWORD_NOTE, password);
                } else {
                    session.removeNote(Constants.SESS_PASSWORD_NOTE);
                }
            }
        }
    }

    @Override
    public void login(String username, String password, Request request) throws ServletException {
        Principal principal = doLogin(request, username, password);
        register(request, request.getResponse(), principal, getAuthMethod(), username, password);
    }

    protected abstract String getAuthMethod();

    /**
     * Process the login request.
     *
     * @param request
     *            Associated request
     * @param username
     *            The user
     * @param password
     *            The password
     * @return The authenticated Principal
     * @throws ServletException
     *             No principal was authenticated with the specified credentials
     */
    protected Principal doLogin(Request request, String username, String password)
            throws ServletException {
        Principal p = context.getRealm().authenticate(username, password);
        if (p == null) {
            throw new ServletException(sm.getString("authenticator.loginFail"));
        }
        return p;
    }

    @Override
    public void logout(Request request) {
        Principal p = request.getPrincipal();
        if (p instanceof TomcatPrincipal) {
            try {
                ((TomcatPrincipal) p).logout();
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.debug(sm.getString("authenticator.tomcatPrincipalLogoutFail"), t);
            }
        }

        register(request, request.getResponse(), null, null, null, null);
    }


    /**
     * Start this component and implement the requirements of
     * {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @exception LifecycleException
     *                if this component detects a fatal error that prevents this
     *                component from being used
     */
    @Override
    protected synchronized void startInternal() throws LifecycleException {
        ServletContext servletContext = context.getServletContext();
        jaspicAppContextID = servletContext.getVirtualServerName() + " " +
                servletContext.getContextPath();

        sessionIdGenerator = new StandardSessionIdGenerator();
        sessionIdGenerator.setSecureRandomAlgorithm(getSecureRandomAlgorithm());
        sessionIdGenerator.setSecureRandomClass(getSecureRandomClass());
        sessionIdGenerator.setSecureRandomProvider(getSecureRandomProvider());

        super.startInternal();
    }

    /**
     * Stop this component and implement the requirements of
     * {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @exception LifecycleException
     *                if this component detects a fatal error that prevents this
     *                component from being used
     */
    @Override
    protected synchronized void stopInternal() throws LifecycleException {

        super.stopInternal();
    }


    @Override
    public void notify(String layer, String appContext) {
    }
}