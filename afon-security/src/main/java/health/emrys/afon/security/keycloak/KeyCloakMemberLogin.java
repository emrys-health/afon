package health.emrys.afon.security.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The login of a member to a project's client, as a browser does it and with no browser: the authorization code flow, with PKCE, that the client's own flow
 * guards, see {@link KeyCloakProjectFlow}.  It asks for the page of the realm, which asks for the password, then for the one-time password of the member's
 * authenticator, and is redirected to the client's redirect URI with a code, which the client gives, with its secret, for the token.  The token is the one that
 * JupyterHub gives the member's notebook: it carries the project, the audiences of the project's Data Providers and the {@code acr} of the second factor.
 * <p>
 * The redirect is never followed, only read, so the client's redirect URI need not be reachable.  A member who has an authenticator signs in with its secret.  A
 * member who has none is walked through enrolling one by the realm, and if no secret is given the login answers that page too, with the code of the secret that
 * the page shows, which the result gives back: that is how a member whose authenticator is to be known, by the tests, is made one.
 */
public final class KeyCloakMemberLogin {

    /** The most pages that a login goes through before it is given up: the password, the second factor, and a redirect or two between. */
    private static final int MAX_STEPS = 12;
    /** The most pages that a sign-in to the hub goes through: the gate, the realm for the gate and for the hub, and the hub, with the redirects between. */
    private static final int MAX_HUB_STEPS = 40;
    /** The button of the gate in front of the hub, a form that goes to its start, with the address to return to as a hidden field. */
    private static final Pattern GATE_FORM = Pattern.compile("(?s)<form[^>]*\\saction=[\"'](/oauth2/start[^\"']*)[\"']");
    /** The link of the hub's login page that signs in with the realm. */
    private static final Pattern HUB_LOGIN_LINK = Pattern.compile("href=[\"']([^\"']*oauth_login[^\"']*)[\"']");
    private static final Pattern HIDDEN_FIELD = Pattern.compile("<input[^>]*type=[\"']hidden[\"'][^>]*name=[\"']([^\"']+)[\"'][^>]*value=[\"']([^\"']*)[\"']");

    /** What marks the message of a refusal in the pages of the realm, in its themes: the error alert, the error page and the error of an input. */
    private static final Pattern MARKED_AS_ERROR = Pattern.compile("(?is)<(span|div|p|li)\\b[^>]*(?:error|feedback|alert|danger)[^>]*>(.*?)</\\1>");
    /** The secret that the page of the enrolment of an authenticator shows, and carries as a field of its form. */
    private static final Pattern TOTP_SECRET = Pattern.compile("name=\"totpSecret\"[^>]*?value=\"([^\"]+)\"|value=\"([^\"]+)\"[^>]*?name=\"totpSecret\"");
    private static final Pattern FORM_ACTION = Pattern.compile("(?s)<form[^>]*\\saction=\"([^\"]+)\"");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI realm;
    private final HttpClient http;
    private final OneTimePassword passwords;

    /**
     * @param realm the realm's URL, {@code https://<oidc domain>/realms/polaris}, which is the issuer of the tokens
     */
    public KeyCloakMemberLogin(URI realm) {
        this(realm, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20)).build(), new OneTimePassword());
    }

    KeyCloakMemberLogin(URI realm, HttpClient http, OneTimePassword passwords) {
        this.realm = realm;
        this.http = http;
        this.passwords = passwords;
    }

    /**
     * What a login gave: the access token, its claims, and the secret of the authenticator that the member signed in with, which is the one that the login
     * enrolled if it was given none.
     */
    public record Token(String accessToken, JsonNode claims, String totpSecret) {
    }

    /** A login that ended in the realm's refusal, or in a page that this does not know how to answer. */
    public static final class MemberLoginException extends IOException {
        public MemberLoginException(String message) {
            super(message);
        }
    }

    /**
     * Logs the member in to the client, and gives the client's token.
     *
     * @param clientId     the id of the project's client, {@code <project-stack>-tre}
     * @param clientSecret the client's secret, which it presents for the code
     * @param redirectUri  a redirect URI that the client has, eg the {@code /hub/oauth_callback} of the TRE's JupyterHub, which is not called
     * @param totpSecret   the secret of the member's authenticator, or {@code null} if they have none and the login is to enrol one
     * @throws MemberLoginException if the realm refuses the login, eg for a wrong password, a code or a user that is not a member, or the login ends
     *                              where it cannot go on
     */
    public Token login(String clientId, String clientSecret, String redirectUri, String username, String password, String totpSecret)
        throws IOException, InterruptedException {
        return login(clientId, clientSecret, redirectUri, username, password, totpSecret, enrolled -> { });
    }

    /**
     * As {@link #login(String, String, String, String, String, String)}, telling {@code enrolled} the secret as soon as the realm has accepted the enrolment of the
     * authenticator, so that it is not lost if the login fails after it, as the realm then has an authenticator that nothing else knows the secret of.
     */
    public Token login(String clientId, String clientSecret, String redirectUri, String username, String password, String totpSecret,
                       java.util.function.Consumer<String> enrolled) throws IOException, InterruptedException {
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        String state = UUID.randomUUID().toString();
        Walk walk = new Walk(username, password, totpSecret, enrolled);
        URI current = URI.create(realm + "/protocol/openid-connect/auth?" + query(Map.of(
            "client_id", clientId, "redirect_uri", redirectUri, "response_type", "code", "scope", "openid", "state", state,
            "code_challenge", challenge(verifier), "code_challenge_method", "S256")));
        HttpResponse<String> response = walk.get(current);
        for (int step = 0; step < MAX_STEPS; step++) {
            if (response.statusCode() / 100 == 3) {
                walk.enrolmentAccepted();
                URI location = current.resolve(response.headers().firstValue("Location").orElseThrow(() -> new MemberLoginException("A redirect with no Location")));
                if (location.toString().startsWith(redirectUri)) {
                    return exchange(clientId, clientSecret, redirectUri, verifier, state, location, walk.secret[0]);
                }
                current = location;
                response = walk.get(current);
                continue;
            }
            current = response.uri();
            response = walk.answerRealm(current, response.body());
        }
        throw new MemberLoginException("The login did not end after " + MAX_STEPS + " pages");
    }

    /**
     * Where a member's sign-in to a TRE's JupyterHub ended: the address and the status of the last page, and its text.
     */
    public record HubLanding(URI at, int status, String text) {
    }

    /**
     * Signs the member in to the TRE's JupyterHub, as a browser does at its address: through the gate in front of it, which sends them to the realm, then to the hub, which
     * signs them in to the realm as the client, and starts their notebook server, for which it keeps their token as it is then.  It follows the redirects, answers the
     * pages of the realm as {@link #login} does, presses the buttons that the gate and the hub show, and ends at the first page that is none of those, which is the hub's
     * page for the server that is starting, or Guacamole's, once it has started.
     *
     * @param entry      the address of the TRE's JupyterHub, {@code https://<jupyter domain>/}
     * @param totpSecret the secret of the member's authenticator
     */
    public HubLanding signInToHub(URI entry, String username, String password, String totpSecret) throws IOException, InterruptedException {
        Walk walk = new Walk(username, password, totpSecret, enrolled -> { });
        URI current = entry;
        HttpResponse<String> response = walk.get(current);
        for (int step = 0; step < MAX_HUB_STEPS; step++) {
            if (response.statusCode() / 100 == 3) {
                current = current.resolve(response.headers().firstValue("Location").orElseThrow(() -> new MemberLoginException("A redirect with no Location")));
                response = walk.get(current);
                continue;
            }
            current = response.uri();
            String page = response.body();
            if (current.getHost().equalsIgnoreCase(realm.getHost())) {
                response = walk.answerRealm(current, page);
                continue;
            }
            // a page of the TRE: the gate's button, or the hub's link to its login, are pressed; any other is where the sign-in has got to
            Matcher button = GATE_FORM.matcher(page);
            if (button.find()) {
                response = walk.get(current.resolve(button.group(1).replace("&amp;", "&") + "?" + hiddenFields(page)));
                continue;
            }
            Matcher link = HUB_LOGIN_LINK.matcher(page);
            if (link.find()) {
                response = walk.get(current.resolve(link.group(1).replace("&amp;", "&")));
                continue;
            }
            return new HubLanding(current, response.statusCode(), textOf(page));
        }
        throw new MemberLoginException("The sign-in to the hub did not end after " + MAX_HUB_STEPS + " pages");
    }

    /** @return the hidden fields of the first form of the page, as a query */
    private static String hiddenFields(String page) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher hidden = HIDDEN_FIELD.matcher(page);
        while (hidden.find()) {
            fields.put(hidden.group(1), hidden.group(2).replace("&amp;", "&"));
        }
        return query(fields);
    }

    /** A walk of the pages of a sign-in: its cookies, which are kept for the host that set them, and what it has sent, answered and learnt of the authenticator. */
    private final class Walk {
        private final Map<String, Map<String, String>> cookies = new LinkedHashMap<>();
        private final String username;
        private final String password;
        private final String[] secret;
        private final boolean enrolling;
        private final java.util.function.Consumer<String> enrolled;
        private String sent;
        private boolean announced;
        /** How many times the one-time password has been sent again, after the realm refused it. */
        private int codeRetries;

        Walk(String username, String password, String totpSecret, java.util.function.Consumer<String> enrolled) {
            this.username = username;
            this.password = password;
            this.secret = new String[]{totpSecret};
            this.enrolling = totpSecret == null;
            this.enrolled = enrolled;
        }

        HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
            return KeyCloakMemberLogin.this.send(cookies, uri, HttpRequest.newBuilder(uri).GET());
        }

        /** Tells that the realm has accepted the enrolment, once, as that is when the secret is the member's. */
        void enrolmentAccepted() {
            if (enrolling && secret[0] != null && !announced) {
                announced = true;
                enrolled.accept(secret[0]);
            }
        }

        /** Answers the page of the realm that asks for the password, the one-time password, or to enrol an authenticator. */
        HttpResponse<String> answerRealm(URI current, String page) throws IOException, InterruptedException {
            // the form that was just sent, shown again, is the realm's refusal of what was sent, which sending again does not change, and a code that it refused is
            // one more that it will refuse: it stops here, saying why.  It is the form shown again that says so, not any markup for errors, which a page has whether
            // or not it has one
            if (sent != null && page.contains("name=\"" + sent + "\"")) {
                // a code is good once, for the realm, whoever sent it: another sign-in of the member in the same 30 seconds, from another process, may have used the one that
                // this has sent, and a code of the next time step is accepted too.  So the code is sent again, of another step; and if others have taken those of the steps that
                // the realm takes, once more after the next step has begun.  With the wrong secret, that is two more failures
                if ("otp".equals(sent) && codeRetries < 2) {
                    codeRetries++;
                } else {
                    throw new MemberLoginException("The realm refused the login: " + refusalIn(page));
                }
            }
            enrolmentAccepted();
            Matcher action = FORM_ACTION.matcher(page);
            if (!action.find()) {
                throw new MemberLoginException("The login stopped at a page with no form: " + textOf(page));
            }
            Map<String, String> fields = fieldsFor(page, username, password, secret, codeRetries == 2);
            sent = fields.keySet().iterator().next();
            URI target = current.resolve(action.group(1).replace("&amp;", "&"));
            return KeyCloakMemberLogin.this.send(cookies, target, HttpRequest.newBuilder(target).header("Content-Type", "application/x-www-form-urlencoded")
                                                                      .POST(HttpRequest.BodyPublishers.ofString(query(fields))));
        }
    }

    /** What the page asks for: the password, or the one-time password, or else nothing that this can give. */
    private Map<String, String> fieldsFor(String page, String username, String password, String[] secret, boolean afterTheNextStep) throws InterruptedException, MemberLoginException {
        Map<String, String> fields = new LinkedHashMap<>();
        if (page.contains("name=\"username\"")) {
            fields.put("username", username);
            fields.put("password", password);
        } else if (page.contains("name=\"totpSecret\"")) {
            if (secret[0] != null) {
                throw new MemberLoginException("The realm asks " + username + " to enrol an authenticator, though a secret was given: the member has none, so the secret is not theirs");
            }
            Matcher shown = TOTP_SECRET.matcher(page);
            if (!shown.find()) {
                throw new MemberLoginException("The realm asks " + username + " to enrol an authenticator, and the page shows no secret to enrol");
            }
            secret[0] = shown.group(1) != null ? shown.group(1) : shown.group(2);
            fields.put("totp", passwords.next(secret[0]));
            fields.put("totpSecret", secret[0]);
            fields.put("userLabel", "afon-smoke-tests");
        } else if (page.contains("name=\"otp\"")) {
            if (secret[0] == null) {
                throw new MemberLoginException("The realm asks " + username + " for a one-time password, and no authenticator secret was given");
            }
            fields.put("otp", afterTheNextStep ? passwords.afterTheNextStep(secret[0]) : passwords.next(secret[0]));
        } else {
            throw new MemberLoginException("The login stopped: " + textOf(page.substring(Math.max(0, page.indexOf("kc-error-message")))));
        }
        return fields;
    }

    /** What the client is given for the code, which it presents with its secret and the verifier of its challenge. */
    private Token exchange(String clientId, String clientSecret, String redirectUri, String verifier, String state, URI location, String totpSecret)
        throws IOException, InterruptedException {
        Map<String, String> returned = new LinkedHashMap<>();
        for (String pair : location.getRawQuery() == null ? new String[0] : location.getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            returned.put(kv[0], kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        if (returned.containsKey("error")) {
            throw new MemberLoginException("The realm refused the login: " + returned.get("error") + " " + returned.getOrDefault("error_description", ""));
        }
        if (!state.equals(returned.get("state")) || returned.get("code") == null) {
            throw new MemberLoginException("The redirect has no code, or another state than the login's: " + location.getRawQuery());
        }
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(realm + "/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(query(Map.of("grant_type", "authorization_code", "code", returned.get("code"), "redirect_uri", redirectUri,
                                                                   "client_id", clientId, "client_secret", clientSecret, "code_verifier", verifier)))).build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new MemberLoginException("The token endpoint answered HTTP " + response.statusCode() + ": " + response.body());
        }
        String accessToken = MAPPER.readTree(response.body()).path("access_token").asText();
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2) {
            throw new MemberLoginException("The token endpoint gave no access token");
        }
        return new Token(accessToken, MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1])), totpSecret);
    }

    /** Sends a request as a browser does, with the cookies that the host has set, and keeps those that the answer sets: whichever their attributes, as the realm may be plain http. */
    private HttpResponse<String> send(Map<String, Map<String, String>> cookies, URI uri, HttpRequest.Builder request) throws IOException, InterruptedException {
        Map<String, String> ofHost = cookies.computeIfAbsent(uri.getHost() + ":" + uri.getPort(), host -> new LinkedHashMap<>());
        if (!ofHost.isEmpty()) {
            StringBuilder header = new StringBuilder();
            ofHost.forEach((name, value) -> header.append(header.length() == 0 ? "" : "; ").append(name).append('=').append(value));
            request.header("Cookie", header.toString());
        }
        HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        for (String cookie : response.headers().allValues("Set-Cookie")) {
            String pair = cookie.contains(";") ? cookie.substring(0, cookie.indexOf(';')) : cookie;
            int at = pair.indexOf('=');
            if (at > 0) {
                String value = pair.substring(at + 1);
                if (value.isEmpty()) {
                    ofHost.remove(pair.substring(0, at));
                } else {
                    ofHost.put(pair.substring(0, at), value);
                }
            }
        }
        return response;
    }

    private static String query(Map<String, String> fields) {
        StringBuilder text = new StringBuilder();
        fields.forEach((name, value) -> text.append(text.length() == 0 ? "" : "&").append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                                            .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return text.toString();
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No SHA-256 to make a PKCE challenge with", e);
        }
    }

    /** @return the messages of the page, those of the elements that Keycloak marks as errors, else what the page says */
    private static String refusalIn(String page) {
        StringBuilder messages = new StringBuilder();
        Matcher marked = MARKED_AS_ERROR.matcher(page);
        while (marked.find()) {
            String text = textOf(marked.group(2));
            if (!text.isEmpty() && messages.indexOf(text) < 0) {
                messages.append(messages.length() == 0 ? "" : "; ").append(text);
            }
        }
        String message = messages.length() > 0 ? messages.toString() : "the form was shown again: " + textOf(page);
        return message.substring(0, Math.min(300, message.length()));
    }

    private static String textOf(String page) {
        return page.replaceAll("(?s)<(script|style).*?</\\1>", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
