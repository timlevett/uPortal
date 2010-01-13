/**
 * Copyright (c) 2000-2009, Jasig, Inc.
 * See license distributed with this file and available online at
 * https://www.ja-sig.org/svn/jasig-parent/tags/rel-10/license-header.txt
 */
package org.jasig.portal.user;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.commons.lang.Validate;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jasig.portal.GuestUserInstance;
import org.jasig.portal.GuestUserPreferencesManager;
import org.jasig.portal.PortalException;
import org.jasig.portal.UserInstance;
import org.jasig.portal.security.IPerson;
import org.jasig.portal.security.IPersonManager;
import org.jasig.portal.security.ISecurityContext;
import org.jasig.portal.security.PortalSecurityException;
import org.jasig.portal.spring.web.context.support.HttpSessionDestroyedEvent;
import org.jasig.portal.url.IPortalRequestUtils;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

/**
 * Determines which user instance object to use for a given user.
 *
 * @author Peter Kharchenko  {@link <a href="mailto:pkharchenko@interactivebusiness.com"">pkharchenko@interactivebusiness.com"</a>}
 * @version $Revision 1.1$
 */
public class UserInstanceManagerImpl implements IUserInstanceManager, ApplicationListener {
    protected final Log logger = LogFactory.getLog(UserInstanceManagerImpl.class);
    
    private Map<Integer, GuestUserPreferencesManager> guestUserPreferencesManagers = new HashMap<Integer, GuestUserPreferencesManager>();
    
    private IPersonManager personManager;
    private IPortalRequestUtils portalRequestUtils;
    
    /**
     * @return the personManager
     */
    public IPersonManager getPersonManager() {
        return personManager;
    }
    /**
     * @param personManager the personManager to set
     */
    @Required
    public void setPersonManager(IPersonManager personManager) {
        Validate.notNull(personManager);
        this.personManager = personManager;
    }
    
    public IPortalRequestUtils getPortalRequestUtils() {
        return portalRequestUtils;
    }
    /**
     * @param portalRequestUtils 
     */
    @Required
    public void setPortalRequestUtils(IPortalRequestUtils portalRequestUtils) {
        this.portalRequestUtils = portalRequestUtils;
    }
    
    
    /**
     * Returns the UserInstance object that is associated with the given request.
     * @param request Incoming HttpServletRequest
     * @return UserInstance object associated with the given request
     */
    public IUserInstance getUserInstance(HttpServletRequest request) throws PortalException {
        try {
            request = this.portalRequestUtils.getOriginalPortalRequest(request);
        }
        catch (IllegalArgumentException iae) {
            //ignore, just means that this isn't a wrapped request
        }
        
        final IPerson person;
        try {
            // Retrieve the person object that is associated with the request
            person = this.personManager.getPerson(request);
        }
        catch (Exception e) {
            logger.error("Exception while retrieving IPerson!", e);
            throw new PortalSecurityException("Could not retrieve IPerson", e);
        }
        
        if (person == null) {
            throw new PortalSecurityException("PersonManager returned null person for this request.  With no user, there's no UserInstance.  Is PersonManager misconfigured?  RDBMS access misconfigured?");
        }

        final HttpSession session = request.getSession(false);
        if (session == null) {
            throw new IllegalStateException("An existing HttpSession is required while retrieving a UserInstance for a HttpServletRequest");
        }

        // Return the UserInstance object if it's in the session
        UserInstanceHolder userInstanceHolder = getUserInstanceHolder(session);
        if (userInstanceHolder != null) {
            final IUserInstance userInstance = userInstanceHolder.getUserInstance();
            
            if (userInstance != null) {
                return userInstance;
            }
        }

        // Create either a UserInstance or a GuestUserInstance
        final IUserInstance userInstance;
        if (person.isGuest()) {
            final Integer personId = person.getID();
            
            //Get or Create a shared GuestUserPreferencesManager for the Guest IPerson
            //sync so multiple managers aren't created for a single guest
            GuestUserPreferencesManager guestUserPreferencesManager;
            synchronized (guestUserPreferencesManagers) {
                guestUserPreferencesManager = guestUserPreferencesManagers.get(personId);
                if (guestUserPreferencesManager == null) {
                    guestUserPreferencesManager = new GuestUserPreferencesManager(person);
                    guestUserPreferencesManagers.put(personId, guestUserPreferencesManager);
                }
            }
            
            userInstance = new GuestUserInstance(person, guestUserPreferencesManager, request);
        }
        else {
            final ISecurityContext securityContext = person.getSecurityContext();
            if (securityContext.isAuthenticated()) {
                userInstance = new UserInstance(person, request);
            }
            else {
                // we can't allow for unauthenticated, non-guest user to come into the system
                throw new PortalSecurityException("System does not allow for unauthenticated non-guest users.");
            }
        }

        //Ensure the newly created UserInstance is cached in the session
        if (userInstanceHolder == null) {
            userInstanceHolder = new UserInstanceHolder();
        }
        userInstanceHolder.setUserInstance(userInstance);
        session.setAttribute(UserInstanceHolder.KEY, userInstanceHolder);

        // Return the new UserInstance
        return userInstance;
    }
    
    /* (non-Javadoc)
     * @see org.springframework.context.ApplicationListener#onApplicationEvent(org.springframework.context.ApplicationEvent)
     */
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof HttpSessionDestroyedEvent) {
            final HttpSession session = ((HttpSessionDestroyedEvent)event).getSession();

            final UserInstanceHolder userInstanceHolder = this.getUserInstanceHolder(session);
            if (userInstanceHolder == null) {
                return;
            }
            
            final IUserInstance userInstance = userInstanceHolder.getUserInstance();
            if (userInstance != null) {
                userInstance.destroySession(session);
            }
        }
    }

    protected UserInstanceHolder getUserInstanceHolder(final HttpSession session) {
        return (UserInstanceHolder) session.getAttribute(UserInstanceHolder.KEY);
    }

    /**
     * <p>Serializable wrapper class so the UserInstance object can
     * be indirectly stored in the session. The manager can deal with
     * this class returning a null value and its field is transient
     * so the session can be serialized successfully with the
     * UserInstance object in it.</p>
     * <p>Implements HttpSessionBindingListener and delegates those methods to
     * the wrapped UserInstance, if present.</p>
     */
    private static class UserInstanceHolder implements Serializable {
        private static final long serialVersionUID = 1L;

        public transient static final String KEY = UserInstanceHolder.class.getName();

        private transient IUserInstance ui = null;

        /**
         * @return Returns the userInstance.
         */
        protected IUserInstance getUserInstance() {
            return this.ui;
        }

        /**
         * @param userInstance The userInstance to set.
         */
        protected void setUserInstance(IUserInstance userInstance) {
            this.ui = userInstance;
        }
    }
}
