/* ********************************************************************
    Licensed to Jasig under one or more contributor license
    agreements. See the NOTICE file distributed with this work
    for additional information regarding copyright ownership.
    Jasig licenses this file to you under the Apache License,
    Version 2.0 (the "License"); you may not use this file
    except in compliance with the License. You may obtain a
    copy of the License at:

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on
    an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied. See the License for the
    specific language governing permissions and limitations
    under the License.
*/
package edu.rpi.cct.webdav.servlet.common;

import edu.rpi.cct.webdav.servlet.common.MethodBase.MethodInfo;
import edu.rpi.cct.webdav.servlet.shared.WebdavException;
import edu.rpi.cct.webdav.servlet.shared.WebdavForbidden;
import edu.rpi.cct.webdav.servlet.shared.WebdavNsIntf;
import edu.rpi.sss.util.servlets.io.CharArrayWrappedResponse;
import edu.rpi.sss.util.xml.XmlEmit;
import edu.rpi.sss.util.xml.tagdefs.WebdavTags;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Enumeration;
import java.util.HashMap;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;
import javax.xml.namespace.QName;

/** WebDAV Servlet.
 * This abstract servlet handles the request/response nonsense and calls
 * abstract routines to interact with an underlying data source.
 *
 * @author Mike Douglass   douglm@rpi.edu
 * @version 1.0
 */
public abstract class WebdavServlet extends HttpServlet
        implements HttpSessionListener {
  protected boolean debug;

  protected boolean dumpContent;

  /* If true we don't invalidate the session - this might allow the application
   * to be used as the server for CAS authenticated widgets etc.
   */
  protected boolean webVersion;

  protected transient Logger log;

  /** Table of methods - set at init
   */
  protected HashMap<String, MethodInfo> methods = new HashMap<String, MethodInfo>();

  /* Try to serialize requests from a single session
   * This is very imperfect.
   */
  static class Waiter {
    boolean active;
    int waiting;
  }

  private static volatile HashMap<String, Waiter> waiters = new HashMap<String, Waiter>();

  @Override
  public void init(final ServletConfig config) throws ServletException {
    super.init(config);

    dumpContent = "true".equals(config.getInitParameter("dumpContent"));
    webVersion = "true".equals(config.getInitParameter("web-version"));

    addMethods();
  }

  /** Get an interface for the namespace
   *
   * @param req       HttpServletRequest
   * @return WebdavNsIntf  or subclass of
   * @throws WebdavException
   */
  public abstract WebdavNsIntf getNsIntf(HttpServletRequest req)
      throws WebdavException;

  @Override
  protected void service(final HttpServletRequest req,
                         HttpServletResponse resp)
      throws ServletException, IOException {
    WebdavNsIntf intf = null;
    boolean serverError = false;

    try {
      debug = getLogger().isDebugEnabled();

      if (debug) {
        debugMsg("entry: " + req.getMethod());
        dumpRequest(req);
      }

      tryWait(req, true);

      intf = getNsIntf(req);

      if (req.getCharacterEncoding() == null) {
        req.setCharacterEncoding("UTF-8");
        if (debug) {
          debugMsg("No charset specified in request; forced to UTF-8");
        }
      }

      if (debug && dumpContent) {
        resp = new CharArrayWrappedResponse(resp,
                                            getLogger());
      }

      String methodName = req.getHeader("X-HTTP-Method-Override");

      if (methodName == null) {
        methodName = req.getMethod();
      }

      MethodBase method = intf.getMethod(methodName);

      //resp.addHeader("DAV", intf.getDavHeader());

      if (method == null) {
        logIt("No method for '" + methodName + "'");

        // ================================================================
        //     Set the correct response
        // ================================================================
        resp.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
      } else {
        method.doMethod(req, resp);
      }
    } catch (WebdavForbidden wdf) {
      sendError(intf, wdf, resp);
    } catch (Throwable t) {
      serverError = handleException(intf, t, resp, serverError);
    } finally {
      if (intf != null) {
        try {
          intf.close();
        } catch (Throwable t) {
          serverError = handleException(intf, t, resp, serverError);
        }
      }

      try {
        tryWait(req, false);
      } catch (Throwable t) {}

      if (debug && dumpContent &&
          (resp instanceof CharArrayWrappedResponse)) {
        /* instanceof check because we might get a subsequent exception before
         * we wrap the response
         */
        CharArrayWrappedResponse wresp = (CharArrayWrappedResponse)resp;

        if (wresp.getUsedOutputStream()) {
          debugMsg("------------------------ response written to output stream -------------------");
        } else {
          String str = wresp.toString();

          debugMsg("------------------------ Dump of response -------------------");
          debugMsg(str);
          debugMsg("---------------------- End dump of response -----------------");

          byte[] bs = str.getBytes();
          resp = (HttpServletResponse)wresp.getResponse();
          debugMsg("contentLength=" + bs.length);
          resp.setContentLength(bs.length);
          resp.getOutputStream().write(bs);
        }
      }

      if (!webVersion) {
        /* WebDAV is stateless - toss away the session */
        try {
          HttpSession sess = req.getSession(false);
          if (sess != null) {
            sess.invalidate();
          }
        } catch (Throwable t) {}
      }
    }
  }

  /* Return true if it's a server error */
  private boolean handleException(final WebdavNsIntf intf, final Throwable t,
                                  final HttpServletResponse resp,
                                  boolean serverError) {
    if (serverError) {
      return true;
    }

    try {
      if (t instanceof WebdavException) {
        WebdavException wde = (WebdavException)t;

        int status = wde.getStatusCode();
        if (status == HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
          getLogger().error(this, wde);
          serverError = true;
        }
        sendError(intf, wde, resp);
        return serverError;
      }

      getLogger().error(this, t);
      sendError(intf, t, resp);
      return true;
    } catch (Throwable t1) {
      // Pretty much screwed if we get here
      return true;
    }
  }

  private void sendError(final WebdavNsIntf intf, final Throwable t,
                         final HttpServletResponse resp) {
    try {
      intf.rollback();

      if (t instanceof WebdavException) {
        WebdavException wde = (WebdavException)t;
        QName errorTag = wde.getErrorTag();

        if (errorTag != null) {
          if (debug) {
            debugMsg("setStatus(" + wde.getStatusCode() + ")" +
                     " message=" + wde.getMessage());
          }
          resp.setStatus(wde.getStatusCode());
          resp.setContentType("text/xml; charset=UTF-8");
          if (!emitError(intf, errorTag, wde.getMessage(),
                         resp.getWriter())) {
            StringWriter sw = new StringWriter();
            emitError(intf, errorTag, wde.getMessage(), sw);

            try {
              if (debug) {
                debugMsg("setStatus(" + wde.getStatusCode() + ")" +
                         " message=" + wde.getMessage());
              }
              resp.sendError(wde.getStatusCode(), sw.toString());
            } catch (Throwable t1) {
            }
          }
        } else {
          if (debug) {
            debugMsg("setStatus(" + wde.getStatusCode() + ")" +
                     " message=" + wde.getMessage());
          }
          resp.sendError(wde.getStatusCode(), wde.getMessage());
        }
      } else {
        if (debug) {
          debugMsg("setStatus(" + HttpServletResponse.SC_INTERNAL_SERVER_ERROR + ")" +
                   " message=" + t.getMessage());
        }
        resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                       t.getMessage());
      }
    } catch (Throwable t1) {
      // Pretty much screwed if we get here
    }
  }

  private boolean emitError(final WebdavNsIntf intf,
                            final QName errorTag,
                            final String extra,
                            final Writer wtr) {
    try {
      XmlEmit xml = new XmlEmit();
      intf.addNamespace(xml);

      xml.startEmit(wtr);
      xml.openTag(WebdavTags.error);

      intf.emitError(errorTag, extra, xml);

      xml.closeTag(WebdavTags.error);
      xml.flush();

      return true;
    } catch (Throwable t1) {
      // Pretty much screwed if we get here
      return false;
    }
  }
  /** Add methods for this namespace
   *
   */
  protected void addMethods() {
    methods.put("ACL", new MethodInfo(AclMethod.class, false));
    methods.put("COPY", new MethodInfo(CopyMethod.class, false));
    methods.put("GET", new MethodInfo(GetMethod.class, false));
    methods.put("HEAD", new MethodInfo(HeadMethod.class, false));
    methods.put("OPTIONS", new MethodInfo(OptionsMethod.class, false));
    methods.put("PROPFIND", new MethodInfo(PropFindMethod.class, false));

    methods.put("DELETE", new MethodInfo(DeleteMethod.class, true));
    methods.put("MKCOL", new MethodInfo(MkcolMethod.class, true));
    methods.put("MOVE", new MethodInfo(MoveMethod.class, true));
    methods.put("POST", new MethodInfo(PostMethod.class, true));
    methods.put("PROPPATCH", new MethodInfo(PropPatchMethod.class, true));
    methods.put("PUT", new MethodInfo(PutMethod.class, true));

    //methods.put("LOCK", new MethodInfo(LockMethod.class, true));
    //methods.put("UNLOCK", new MethodInfo(UnlockMethod.class, true));
  }

  private void tryWait(final HttpServletRequest req, final boolean in) throws Throwable {
    Waiter wtr = null;
    synchronized (waiters) {
      //String key = req.getRequestedSessionId();
      String key = req.getRemoteUser();
      if (key == null) {
        return;
      }

      wtr = waiters.get(key);
      if (wtr == null) {
        if (!in) {
          return;
        }

        wtr = new Waiter();
        wtr.active = true;
        waiters.put(key, wtr);
        return;
      }
    }

    synchronized (wtr) {
      if (!in) {
        wtr.active = false;
        wtr.notify();
        return;
      }

      wtr.waiting++;
      while (wtr.active) {
        if (debug) {
          log.debug("in: waiters=" + wtr.waiting);
        }

        wtr.wait();
      }
      wtr.waiting--;
      wtr.active = true;
    }
  }

  /* (non-Javadoc)
   * @see javax.servlet.http.HttpSessionListener#sessionCreated(javax.servlet.http.HttpSessionEvent)
   */
  @Override
  public void sessionCreated(final HttpSessionEvent se) {
  }

  /* (non-Javadoc)
   * @see javax.servlet.http.HttpSessionListener#sessionDestroyed(javax.servlet.http.HttpSessionEvent)
   */
  @Override
  public void sessionDestroyed(final HttpSessionEvent se) {
    HttpSession session = se.getSession();
    String sessid = session.getId();
    if (sessid == null) {
      return;
    }

    synchronized (waiters) {
      waiters.remove(sessid);
    }
  }

  /** Debug
   *
   * @param req
   */
  public void dumpRequest(final HttpServletRequest req) {
    Logger log = getLogger();

    try {
      Enumeration names = req.getHeaderNames();

      String title = "Request headers";

      log.debug(title);

      while (names.hasMoreElements()) {
        String key = (String)names.nextElement();
        String val = req.getHeader(key);
        if (key.toLowerCase().equals("authorization") &&
            (val != null) &&
            (val.toLowerCase().startsWith("basic"))) {
          val = "Basic **********";
        }

        log.debug("  " + key + " = \"" + val + "\"");
      }

      names = req.getParameterNames();

      title = "Request parameters";

      log.debug(title + " - global info and uris");
      log.debug("getRemoteAddr = " + req.getRemoteAddr());
      log.debug("getRequestURI = " + req.getRequestURI());
      log.debug("getRemoteUser = " + req.getRemoteUser());
      log.debug("getRequestedSessionId = " + req.getRequestedSessionId());
      log.debug("HttpUtils.getRequestURL(req) = " + req.getRequestURL());
      log.debug("contextPath=" + req.getContextPath());
      log.debug("query=" + req.getQueryString());
      log.debug("contentlen=" + req.getContentLength());
      log.debug("request=" + req);
      log.debug("parameters:");

      log.debug(title);

      while (names.hasMoreElements()) {
        String key = (String)names.nextElement();
        String val = req.getParameter(key);
        log.debug("  " + key + " = \"" + val + "\"");
      }
    } catch (Throwable t) {
    }
  }

  /**
   * @return LOgger
   */
  public Logger getLogger() {
    if (log == null) {
      log = Logger.getLogger(this.getClass());
    }

    return log;
  }

  /** Debug
   *
   * @param msg
   */
  public void debugMsg(final String msg) {
    getLogger().debug(msg);
  }

  /** Info messages
   *
   * @param msg
   */
  public void logIt(final String msg) {
    getLogger().info(msg);
  }

  protected void error(final Throwable t) {
    getLogger().error(this, t);
  }
}
