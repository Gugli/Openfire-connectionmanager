/**
 * $RCSfile$
 * $Revision: $
 * $Date: $
 *
 * Copyright (C) 2006 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution.
 */
package org.jivesoftware.multiplexer.net.http;

import org.jivesoftware.multiplexer.*;
import org.jivesoftware.multiplexer.spi.ClientFailoverDeliverer;
import org.dom4j.Element;
import org.dom4j.DocumentHelper;

import java.util.*;

/**
 * A session represents a serious of interactions with an XMPP client sending packets using the HTTP
 * Binding protocol specified in
 * <a href="http://www.xmpp.org/extensions/xep-0124.html">XEP-0124</a>. A session can have several
 * client connections open simultaneously while awaiting packets bound for the client from the
 * server.
 *
 * @author Alexander Wenckus
 */
public class HttpSession extends Session {
    private int wait;
    private int hold = -1000;
    private String language;
    private final Queue<HttpConnection> connectionQueue = new LinkedList<HttpConnection>();
    private final List<Element> pendingElements = new ArrayList<Element>();
    private boolean isSecure;
    private int maxPollingInterval;
    private long lastPoll = -1;
    private Set<SessionListener> listeners = new HashSet<SessionListener>();
    private boolean isClosed;
    private int inactivityTimeout;

    protected HttpSession(String serverName, String streamID) {
        super(serverName, null, streamID);
    }

    void addConnection(HttpConnection connection, boolean isPoll) throws HttpBindException,
            HttpConnectionClosedException
    {
        if(connection == null) {
            throw new IllegalArgumentException("Connection cannot be null.");
        }

        if(isPoll) {
            checkPollingInterval();
        }

        if(isSecure && !connection.isSecure()) {
            throw new HttpBindException("Session was started from secure connection, all " +
                    "connections on this session must be secured.", false, 403);
        }

        connection.setSession(this);
        if (pendingElements.size() > 0) {
            String deliverable = createDeliverable(pendingElements);
            pendingElements.clear();
            fireConnectionOpened(connection);
            connection.deliverBody(deliverable);
            fireConnectionClosed(connection);
        }
        else {
            // With this connection we need to check if we will have too many connections open,
            // closing any extras.
            while (hold > 0 && connectionQueue.size() >= hold) {
                HttpConnection toClose = connectionQueue.remove();
                toClose.close();
                fireConnectionClosed(toClose);
            }
            connectionQueue.offer(connection);
            fireConnectionOpened(connection);
        }
    }

    private void fireConnectionOpened(HttpConnection connection) {
        Collection<SessionListener> listeners =
                new HashSet<SessionListener>(this.listeners);
        for(SessionListener listener : listeners) {
            listener.connectionOpened(this, connection);
        }
    }

    private void checkPollingInterval() throws HttpBindException {
        long time = System.currentTimeMillis();
        if(lastPoll > 0  && ((lastPoll - time) / 1000) < maxPollingInterval) {
            throw new HttpBindException("Too frequent polling", true, 403);
        }
        lastPoll = time;
    }

    public String getAvailableStreamFeatures() {
        return null;
    }

    public synchronized void close() {
        close(false);
    }

    public synchronized void close(boolean isServerShuttingDown) {
        if(isClosed) {
            return;
        }
        isClosed = true;

        if(pendingElements.size() > 0) {
            failDelivery();
        }

        Collection<SessionListener> listeners =
                new HashSet<SessionListener>(this.listeners);
        this.listeners.clear();
        for(SessionListener listener : listeners) {
            listener.sessionClosed(this);
        }
    }

    private void failDelivery() {
        ClientFailoverDeliverer deliverer = new ClientFailoverDeliverer();
        deliverer.setStreamID(getStreamID());
        for(Element element : pendingElements) {
            deliverer.deliver(element);
        }
        pendingElements.clear();
    }

    public synchronized boolean isClosed() {
        return isClosed;
    }

    public synchronized void deliver(Element stanza) {
        String deliverable = createDeliverable(Arrays.asList(stanza));
        boolean delivered = false;
        while(!delivered && connectionQueue.size() > 0) {
            HttpConnection connection = connectionQueue.remove();
            try {
                connection.deliverBody(deliverable);
                delivered = true;
                fireConnectionClosed(connection);
            }
            catch (HttpConnectionClosedException e) {
                /* Connection was closed, try the next one */
            }
        }

        if(!delivered) {
            pendingElements.add(stanza);
        }
    }

    private void fireConnectionClosed(HttpConnection connection) {
        Collection<SessionListener> listeners =
                new HashSet<SessionListener>(this.listeners);
        for(SessionListener listener : listeners) {
            listener.connectionClosed(this, connection);
        }
    }

    private String createDeliverable(Collection<Element> elements) {
        Element body = DocumentHelper.createElement("body");
        body.addAttribute("xmlns", "http://jabber.org/protocol/httpbind");
        for(Element child : elements) {
            child = child.createCopy();
            child.setParent(null);
            body.add(child);
        }
        return body.asXML();
    }

    /**
     * This attribute specifies the longest time (in seconds) that the connection manager is allowed
     * to wait before responding to any request during the session. This enables the client to
     * prevent its TCP connection from expiring due to inactivity, as well as to limit the delay
     * before it discovers any network failure.
     *
     * @param wait the longest time it is permissible to wait for a response.
     */
    public void setWait(int wait) {
        this.wait = wait;
    }

    /**
     * This attribute specifies the longest time (in seconds) that the connection manager is allowed
     * to wait before responding to any request during the session. This enables the client to
     * prevent its TCP connection from expiring due to inactivity, as well as to limit the delay
     * before it discovers any network failure.
     *
     * @return the longest time it is permissible to wait for a response.
     */
    public int getWait() {
        return wait;
    }

    /**
     * This attribute specifies the maximum number of requests the connection manager is allowed
     * to keep waiting at any one time during the session. (For example, if a constrained client
     * is unable to keep open more than two HTTP connections to the same HTTP server simultaneously,
     * then it SHOULD specify a value of "1".)
     *
     * @param hold the maximum number of simultaneous waiting requests.
     *
     */
    public void setHold(int hold) {
        this.hold = hold;
    }

    /**
     * This attribute specifies the maximum number of requests the connection manager is allowed
     * to keep waiting at any one time during the session. (For example, if a constrained client
     * is unable to keep open more than two HTTP connections to the same HTTP server simultaneously,
     * then it SHOULD specify a value of "1".)
     *
     * @return the maximum number of simultaneous waiting requests
     */
    public int getHold() {
        return hold;
    }

    public void setLanaguage(String language) {
        this.language = language;
    }

    public String getLanguage() {
        return language;
    }

    /**
     * Sets the max interval within which a client can send polling requests. If more than one
     * request occurs in the interval the session will be terminated.
     *
     * @param maxPollingInterval time in seconds a client needs to wait before sending polls to the
     * server, a negative <i>int</i> indicates that there is no limit.
     */
    public void setMaxPollingInterval(int maxPollingInterval) {
        this.maxPollingInterval = maxPollingInterval;
    }

    /**
     * Sets whether the initial request on the session was secure.
     *
     * @param isSecure true if the initial request was secure and false if it wasn't.
     */
    protected void setSecure(boolean isSecure) {
        this.isSecure = isSecure;
    }

    /**
     * Returns true if all connections on this session should be secured, and false if
     * they should not.
     *
     * @return true if all connections on this session should be secured, and false if
     * they should not.
     */
    public boolean isSecure() {
        return isSecure;
    }

    public void addSessionCloseListener(SessionListener listener) {
        listeners.add(listener);
    }

    public void removeSessionCloseListener(SessionListener listener) {
        listeners.remove(listener);
    }

    public void setInactivityTimeout(int inactivityTimeout) {
        this.inactivityTimeout = inactivityTimeout;
    }

    public int getInactivityTimeout() {
        return inactivityTimeout;
    }

    public int getConnectionCount() {
        return connectionQueue.size();
    }
}
