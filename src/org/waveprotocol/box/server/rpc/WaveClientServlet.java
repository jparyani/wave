/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.waveprotocol.box.server.rpc;

import com.google.common.collect.Maps;
import com.google.gxp.base.GxpContext;
import com.google.inject.Inject;
import com.google.inject.name.Named;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.waveprotocol.box.common.SessionConstants;
import org.waveprotocol.box.server.CoreSettings;
import org.waveprotocol.box.server.authentication.SessionManager;
import org.waveprotocol.box.server.gxp.TopBar;
import org.waveprotocol.box.server.gxp.WaveClientPage;
import org.waveprotocol.box.server.util.RandomBase64Generator;
import org.waveprotocol.box.server.account.AccountData;
import org.waveprotocol.box.server.persistence.AccountStore;
import org.waveprotocol.box.server.util.RegistrationUtil;
import org.waveprotocol.box.server.util.UrlParameters;
import org.waveprotocol.wave.client.util.ClientFlagsBase;
import org.waveprotocol.wave.common.bootstrap.FlagConstants;
import org.waveprotocol.wave.model.wave.InvalidParticipantAddress;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.util.logging.Log;
import org.waveprotocol.box.server.account.HumanAccountDataImpl;
import org.waveprotocol.box.server.authentication.PasswordDigest;
import org.waveprotocol.box.server.persistence.PersistenceException;
import com.google.wave.api.WaveService;
import com.google.wave.api.JsonRpcResponse;
import org.waveprotocol.box.server.account.RobotAccountData;
import static org.waveprotocol.box.server.robots.agent.RobotAgentUtil.appendLine;
import com.google.common.collect.Sets;
import com.google.wave.api.Wavelet;
import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.id.WaveletId;
import com.google.wave.api.JsonRpcConstant.ParamsProperty;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.io.Writer;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;

import javax.inject.Singleton;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * The HTTP servlet for serving a wave client along with content generated on
 * the server.
 *
 * @author kalman@google.com (Benjamin Kalman)
 */
@SuppressWarnings("serial")
@Singleton
public class WaveClientServlet extends HttpServlet {

  private static final Log LOG = Log.get(WaveClientServlet.class);

  private static final HashMap<String, String> FLAG_MAP = Maps.newHashMap();
  static {
    // __NAME_MAPPING__ is a map of name to obfuscated id
    for (int i = 0; i < FlagConstants.__NAME_MAPPING__.length; i += 2) {
      FLAG_MAP.put(FlagConstants.__NAME_MAPPING__[i], FlagConstants.__NAME_MAPPING__[i + 1]);
    }
  }

  private final String domain;
  private final String analyticsAccount;
  private final SessionManager sessionManager;
  private final String websocketAddress;
  private final String websocketPresentedAddress;
  private final AccountStore accountStore;

  /**
   * Creates a servlet for the wave client.
   */
  @Inject
  public WaveClientServlet(
      @Named(CoreSettings.WAVE_SERVER_DOMAIN) String domain,
      @Named(CoreSettings.HTTP_FRONTEND_ADDRESSES) List<String> httpAddresses,
      @Named(CoreSettings.HTTP_WEBSOCKET_PUBLIC_ADDRESS) String websocketAddress,
      @Named(CoreSettings.HTTP_WEBSOCKET_PRESENTED_ADDRESS) String websocketPresentedAddress,
      @Named(CoreSettings.ANALYTICS_ACCOUNT) String analyticsAccount,
      SessionManager sessionManager, AccountStore accountStore) {
    this.domain = domain;
    this.websocketAddress = StringUtils.isEmpty(websocketAddress) ?
        httpAddresses.get(0) : websocketAddress;
    this.websocketPresentedAddress = StringUtils.isEmpty(websocketPresentedAddress) ?
        this.websocketAddress : websocketPresentedAddress;
    this.analyticsAccount = analyticsAccount;
    this.sessionManager = sessionManager;
    this.accountStore = accountStore;
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    ParticipantId id = sessionManager.getLoggedInUser(request.getSession(false));

    // Eventually, it would be nice to show users who aren't logged in the public waves.
    // However, public waves aren't implemented yet. For now, we'll just redirect users
    // who haven't signed in to the sign in page.
    if (id == null) {
      String username = request.getHeader("X-Sandstorm-Username");
      String userId = request.getHeader("X-Sandstorm-User-Id");

      if ((username == null || username.isEmpty()) || (userId == null || userId.trim().isEmpty())) {
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "You must be logged into a Sandstorm account to use Wave.");
        return;
      }
      username = username.replace(" ", "_").trim();
      try {
        id = RegistrationUtil.checkNewUsername(domain, username);
      } catch (InvalidParticipantAddress exception) {
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to use Sandstorm username correctly");
        return;
      }

      if(!RegistrationUtil.doesAccountExist(accountStore, id)) {
        HumanAccountDataImpl account = new HumanAccountDataImpl(id, new PasswordDigest(RandomStringUtils.random(64).toCharArray()));
        try {
          accountStore.putAccount(account);
        } catch (PersistenceException e) {
          response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to create new Sandstorm user");
          return;
        }
      }
      // TODO: check userId

      RobotAccountData robotAccount = null;
      String rpcUrl = "http://localhost:9898" + "/robot/rpc";
      String robotId = "welcome-bot";
      try {
        robotAccount = accountStore.getAccount(ParticipantId.ofUnsafe(robotId + "@" + domain)).asRobot();
      } catch (PersistenceException e) {
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Cannot fetch account data for robot");
        return;
      }

      if (robotAccount == null) {
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to fetch account data for robot");
        return;
      }

      WaveService waveService = new WaveService(Long.toHexString(1));
      waveService.setupOAuth(robotAccount.getId().getAddress(), robotAccount.getConsumerSecret(), rpcUrl);
      File waveIdFile = new File("/var/mainWave.txt");
      String waveId;

      if(waveIdFile.exists()) {
        BufferedReader reader = new BufferedReader(new FileReader(waveIdFile));
        waveId = reader.readLine();
        reader.close();
        Wavelet wave = waveService.fetchWavelet(WaveId.deserialise(waveId), WaveletId.of("sandstorm", "conv+root"), rpcUrl);
        wave.getParticipants().add(id.getAddress());
        waveService.submit(wave, rpcUrl);
      } else {
        Wavelet newWelcomeWavelet = waveService.newWave(domain, Sets.newHashSet(id.getAddress()));

        appendLine(newWelcomeWavelet.getRootBlip(), "Welcome to " + domain + "!");
        List<JsonRpcResponse> responses = waveService.submit(newWelcomeWavelet, rpcUrl);
        waveId = responses.get(0).getData().get(ParamsProperty.WAVE_ID).toString();

        Writer writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                  new FileOutputStream(waveIdFile), "utf-8"));
            writer.write(waveId);
            writer.write("\n");
        } catch (IOException ex) {
          response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to write waveId to file");
          return;
        } finally {
           try {writer.close();} catch (Exception ex) {}
        }
      }

      HttpSession session = request.getSession(true);
      sessionManager.setLoggedInUser(session, id);

      if (request.getContextPath().isEmpty()) {
        response.addHeader("Location", "/#" + waveId);
        response.setStatus(302);
        // Can't use response.sendRedirect, since it drops https
        return;
      }
    }

    AccountData account = sessionManager.getLoggedInAccount(request.getSession(false));
    if (account != null) {
      String locale = account.asHuman().getLocale();
      if (locale != null) {
        String requestLocale = UrlParameters.getParameters(request.getQueryString()).get("locale");
        if (requestLocale == null) {
          response.sendRedirect(UrlParameters.addParameter(request.getRequestURL().toString(), "locale", locale));
          return;
        }
      }
    }

    String[] parts = id.getAddress().split("@");
    String username = parts[0];
    String userDomain = id.getDomain();

    try {
      WaveClientPage.write(response.getWriter(), new GxpContext(request.getLocale()),
          getSessionJson(request.getSession(false)), getClientFlags(request), websocketPresentedAddress,
          TopBar.getGxpClosure(username, userDomain), analyticsAccount);
    } catch (IOException e) {
      LOG.warning("Failed to write GXP for request " + request, e);
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      return;
    }

    response.setContentType("text/html");
    response.setStatus(HttpServletResponse.SC_OK);
  }

  private JSONObject getClientFlags(HttpServletRequest request) {
    try {
      JSONObject ret = new JSONObject();

      Enumeration<?> iter = request.getParameterNames();
      while (iter.hasMoreElements()) {
        String name = (String) iter.nextElement();
        String value = request.getParameter(name);

        if (FLAG_MAP.containsKey(name)) {
          // Set using the correct type of data in the json using reflection
          try {
            Method getter = ClientFlagsBase.class.getMethod(name);
            Class<?> retType = getter.getReturnType();

            if (retType.equals(String.class)) {
              ret.put(FLAG_MAP.get(name), value);
            } else if (retType.equals(Integer.class)) {
              ret.put(FLAG_MAP.get(name), Integer.parseInt(value));
            } else if (retType.equals(Boolean.class)) {
              ret.put(FLAG_MAP.get(name), Boolean.parseBoolean(value));
            } else if (retType.equals(Float.class)) {
              ret.put(FLAG_MAP.get(name), Float.parseFloat(value));
            } else if (retType.equals(Double.class)) {
              ret.put(FLAG_MAP.get(name), Double.parseDouble(value));
            } else {
              // Flag exists, but its type is unknown, so it can not be
              // properly encoded in JSON.
              LOG.warning("Ignoring flag [" + name + "] with unknown return type: " + retType);
            }

            // Ignore the flag on any exception
          } catch (SecurityException ex) {
          } catch (NoSuchMethodException ex) {
            LOG.warning("Failed to find the flag [" + name + "] in ClientFlagsBase.");
          } catch (NumberFormatException ex) {
          }
        }
      }

      return ret;
    } catch (JSONException ex) {
      LOG.severe("Failed to create flags JSON");
      return new JSONObject();
    }
  }

  private JSONObject getSessionJson(HttpSession session) {
    try {
      ParticipantId user = sessionManager.getLoggedInUser(session);
      String address = (user != null) ? user.getAddress() : null;

      // TODO(zdwang): Figure out a proper session id rather than generating a
      // random number
      String sessionId = (new RandomBase64Generator()).next(10);

      return new JSONObject()
          .put(SessionConstants.DOMAIN, domain)
          .putOpt(SessionConstants.ADDRESS, address)
          .putOpt(SessionConstants.ID_SEED, sessionId);
    } catch (JSONException e) {
      LOG.severe("Failed to create session JSON");
      return new JSONObject();
    }
  }
}
