package br.com.jobradar.service;

import br.com.jobradar.model.GmailToken;
import br.com.jobradar.repository.GmailTokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Integração com o Gmail — só-leitura (escopo {@code gmail.readonly}), pra
 * achar vagas nos emails de alerta de sites de vaga (LinkedIn, Glassdoor —
 * ver {@link #DOMINIOS_VAGA}) e sugerir importar pro Job Radar (o usuário
 * confirma cada uma antes de qualquer coisa entrar no banco, ver
 * EmailVagasCard no frontend). O Hunter NUNCA escreve, apaga ou marca nada
 * no Gmail — só lê.
 *
 * <p>Sem SDK oficial da Google (mesma filosofia do {@link GeminiService}):
 * chamadas REST cruas via RestTemplate, pra não trazer uma dependência
 * pesada só por causa de um fluxo OAuth relativamente simples.</p>
 *
 * <p><b>Singleton de verdade:</b> Job Radar não tem sistema de usuários —
 * só existe UM {@link GmailToken}, id fixo 1L. Se um dia o app virar
 * multi-usuário, isso precisa ser revisitado.</p>
 */
@Service
@Slf4j
public class GmailService {

    private static final Long TOKEN_ID = 1L;
    private static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";

    @Value("${google.oauth.client-id:}")
    private String clientId;

    @Value("${google.oauth.client-secret:}")
    private String clientSecret;

    @Value("${google.oauth.redirect-uri:http://localhost:8080/api/gmail/oauth/callback}")
    private String redirectUri;

    private final GmailTokenRepository tokenRepository;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public GmailService(GmailTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    public boolean isConnected() {
        return tokenRepository.findById(TOKEN_ID).isPresent();
    }

    public record Status(boolean configured, boolean connected, String email) {}

    public Status status() {
        return tokenRepository.findById(TOKEN_ID)
                .map(t -> new Status(isConfigured(), true, t.getEmailAddress()))
                .orElseGet(() -> new Status(isConfigured(), false, null));
    }

    /**
     * Monta a URL de consentimento do Google — o frontend só redireciona o
     * navegador inteiro pra cá (não é uma chamada fetch/XHR, é navegação de
     * verdade, senão o Google recusa por causa de proteção contra clickjacking
     * em iframe). {@code access_type=offline} + {@code prompt=consent} são o
     * que garante um refresh_token de volta mesmo se o usuário já tiver
     * autorizado antes (sem prompt=consent, reautorizar não manda refresh_token
     * de novo).
     */
    public String buildAuthorizationUrl() {
        String scope = URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);
        String redirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
        return "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + clientId
                + "&redirect_uri=" + redirect
                + "&response_type=code"
                + "&scope=" + scope
                + "&access_type=offline"
                + "&prompt=consent";
    }

    /**
     * Troca o "code" que o Google devolveu no callback por um access+refresh
     * token de verdade, busca o email da conta (só pra exibir), e salva —
     * substitui qualquer conexão anterior (reconectar troca a conta).
     */
    public boolean handleCallback(String code) {
        if (!isConfigured() || code == null || code.isBlank()) return false;
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("code", code);
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            form.add("redirect_uri", redirectUri);
            form.add("grant_type", "authorization_code");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            String response = restTemplate.postForObject(
                    "https://oauth2.googleapis.com/token", new HttpEntity<>(form, headers), String.class);
            JsonNode json = mapper.readTree(response);

            String accessToken = json.path("access_token").asText(null);
            String refreshToken = json.path("refresh_token").asText(null);
            int expiresIn = json.path("expires_in").asInt(3600);
            if (accessToken == null || refreshToken == null) {
                log.warn("Gmail OAuth: resposta sem access_token/refresh_token — usuário provavelmente já tinha " +
                        "autorizado antes sem 'prompt=consent' ter forçado um refresh_token novo.");
                return false;
            }

            String email = buscarEmailDaConta(accessToken);

            GmailToken token = tokenRepository.findById(TOKEN_ID).orElse(new GmailToken());
            token.setId(TOKEN_ID);
            token.setAccessToken(accessToken);
            token.setRefreshToken(refreshToken);
            token.setAccessTokenExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));
            token.setEmailAddress(email);
            token.setConnectedAt(LocalDateTime.now());
            tokenRepository.save(token);
            return true;
        } catch (Exception e) {
            log.warn("Gmail OAuth: falha trocando code por token: {}", e.getMessage());
            return false;
        }
    }

    public void disconnect() {
        tokenRepository.deleteById(TOKEN_ID);
    }

    private String buscarEmailDaConta(String accessToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            String response = restTemplate.exchange(
                    "https://www.googleapis.com/oauth2/v2/userinfo",
                    org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
            return mapper.readTree(response).path("email").asText(null);
        } catch (Exception e) {
            return null; // só cosmético (exibição em Configurações) — não bloqueia nada se falhar
        }
    }

    // Margem de 2 minutos antes do vencimento real, pra nunca usar um token
    // que expira no meio de uma chamada em andamento.
    private String getValidAccessToken() {
        GmailToken token = tokenRepository.findById(TOKEN_ID).orElse(null);
        if (token == null) return null;
        if (token.getAccessToken() != null && token.getAccessTokenExpiresAt() != null
                && token.getAccessTokenExpiresAt().isAfter(LocalDateTime.now().plusMinutes(2))) {
            return token.getAccessToken();
        }
        return refreshAccessToken(token);
    }

    private String refreshAccessToken(GmailToken token) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            form.add("refresh_token", token.getRefreshToken());
            form.add("grant_type", "refresh_token");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            String response = restTemplate.postForObject(
                    "https://oauth2.googleapis.com/token", new HttpEntity<>(form, headers), String.class);
            JsonNode json = mapper.readTree(response);
            String accessToken = json.path("access_token").asText(null);
            int expiresIn = json.path("expires_in").asInt(3600);
            if (accessToken == null) return null;

            token.setAccessToken(accessToken);
            token.setAccessTokenExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));
            tokenRepository.save(token);
            return accessToken;
        } catch (HttpClientErrorException e) {
            // 400 invalid_grant normalmente significa que o usuário revogou o
            // acesso pelo próprio Google (myaccount.google.com/permissions) —
            // apaga o token guardado, senão fica tentando pra sempre.
            log.warn("Gmail: refresh token recusado ({}), desconectando.", e.getStatusCode());
            tokenRepository.deleteById(TOKEN_ID);
            return null;
        } catch (Exception e) {
            log.warn("Gmail: falha ao renovar access token: {}", e.getMessage());
            return null;
        }
    }

    public record EmailJobCandidate(String titulo, String empresa, String url, String dataEmail, String fonte) {}

    public record BuscaResultado(boolean conectado, List<EmailJobCandidate> vagas, String erro) {
        public static BuscaResultado desconectado() {
            return new BuscaResultado(false, List.of(), "Gmail não conectado — conecte em Configurações.");
        }
        public static BuscaResultado erro(String msg) {
            return new BuscaResultado(true, List.of(), msg);
        }
        public static BuscaResultado ok(List<EmailJobCandidate> vagas) {
            return new BuscaResultado(true, vagas, null);
        }
    }

    // Não vasculha a caixa toda — só emails de vaga de um punhado de sites
    // conhecidos (LinkedIn, Glassdoor), dos últimos N dias. Limita a 15
    // emails por chamada (cada um custa uma chamada extra pra buscar o
    // corpo completo) pra não demorar demais numa pergunta de chat.
    private static final int MAX_EMAILS_POR_BUSCA = 15;

    // Bug real reportado: um allowlist fixo de remetentes exatos (só
    // jobalerts-noreply@/jobs-noreply@linkedin.com) deixou passar batido um
    // email de "vagas similares" mandado de outro endereço — sites de vaga
    // usam vários remetentes noreply@ diferentes pra tipos diferentes de
    // notificação, impossível prever todos. Trocado por "from:<domínio>"
    // (qualquer remetente do domínio inteiro) + filtro por ASSUNTO com
    // palavras que só aparecem em email de vaga de verdade — mais amplo,
    // mas ainda não pega notificação de conexão/mensagem/aniversário etc.
    // Domínios cobertos hoje: LinkedIn e Glassdoor (BR e .com). Pedido pra
    // adicionar mais (Indeed, Catho, etc.) — é só somar aqui.
    private static final List<String> DOMINIOS_VAGA = List.of(
            "linkedin.com", "glassdoor.com", "glassdoor.com.br");
    private static final String FILTRO_ASSUNTO =
            "(subject:job OR subject:jobs OR subject:vaga OR subject:vagas OR subject:emprego OR subject:opportunity)";

    public BuscaResultado buscarVagasNosEmails(int diasAtras) {
        if (!isConfigured()) return BuscaResultado.erro("Integração com Gmail não configurada.");
        String accessToken = getValidAccessToken();
        if (accessToken == null) return BuscaResultado.desconectado();

        int dias = diasAtras > 0 ? Math.min(diasAtras, 30) : 7;
        try {
            String fromClause = "(" + DOMINIOS_VAGA.stream().map(d -> "from:" + d)
                    .reduce((a, b) -> a + " OR " + b).orElse("") + ")";
            String query = URLEncoder.encode(
                    fromClause + " " + FILTRO_ASSUNTO + " newer_than:" + dias + "d",
                    StandardCharsets.UTF_8);
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);

            String listResponse = restTemplate.exchange(
                    "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults="
                            + MAX_EMAILS_POR_BUSCA + "&q=" + query,
                    org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
            JsonNode messages = mapper.readTree(listResponse).path("messages");

            List<EmailJobCandidate> candidatas = new ArrayList<>();
            Set<String> urlsVistas = new LinkedHashSet<>();
            for (JsonNode msg : messages) {
                String id = msg.path("id").asText(null);
                if (id == null) continue;
                try {
                    String msgResponse = restTemplate.exchange(
                            "https://gmail.googleapis.com/gmail/v1/users/me/messages/" + id + "?format=full",
                            org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
                    JsonNode msgJson = mapper.readTree(msgResponse);
                    String remetente = extrairRemetente(msgJson);
                    String dataEmail = extrairData(msgJson);
                    String corpo = extrairCorpo(msgJson);
                    if (corpo == null) continue;
                    for (EmailJobCandidate c : extrairVagas(corpo, dataEmail, remetente)) {
                        if (urlsVistas.add(c.url())) candidatas.add(c);
                    }
                } catch (Exception e) {
                    log.warn("Gmail: falha lendo email {}: {}", id, e.getMessage());
                    // segue pros próximos emails — um email malformado não derruba a busca inteira
                }
            }
            return BuscaResultado.ok(candidatas);
        } catch (HttpClientErrorException e) {
            log.warn("Gmail: erro buscando emails ({}): {}", e.getStatusCode(), e.getMessage());
            return BuscaResultado.erro("Gmail recusou a requisição (erro " + e.getStatusCode().value() + ").");
        } catch (RestClientException e) {
            return BuscaResultado.erro("Gmail indisponível no momento (rede/timeout).");
        } catch (Exception e) {
            log.warn("Gmail: erro inesperado buscando vagas nos emails: {}", e.getMessage());
            return BuscaResultado.erro("Erro processando os emails do Gmail.");
        }
    }

    private String extrairData(JsonNode msgJson) {
        return extrairHeader(msgJson, "Date");
    }

    // Usado só pra rotular a fonte no card (ver EmailVagasCard) — "LinkedIn"
    // ou "Glassdoor" a partir do domínio do remetente real do email, mais
    // confiável que tentar adivinhar pelo link (Glassdoor às vezes embrulha
    // o link de vaga num redirecionador de outro domínio).
    private String extrairRemetente(JsonNode msgJson) {
        String from = extrairHeader(msgJson, "From");
        if (from == null) return "Email";
        String lower = from.toLowerCase();
        if (lower.contains("linkedin.com")) return "LinkedIn";
        if (lower.contains("glassdoor")) return "Glassdoor";
        return "Email";
    }

    private String extrairHeader(JsonNode msgJson, String nome) {
        JsonNode headers = msgJson.at("/payload/headers");
        for (JsonNode h : headers) {
            if (nome.equalsIgnoreCase(h.path("name").asText())) {
                return h.path("value").asText(null);
            }
        }
        return null;
    }

    // O corpo de um email pode vir direto em payload.body (mensagens simples)
    // ou espalhado em payload.parts (multipart, o caso normal pra HTML) —
    // prefere a parte text/html (link de vaga é sempre um <a href>, texto
    // puro raramente preserva a URL de forma útil), cai pra text/plain se
    // não achar HTML nenhuma. Busca recursivamente porque multipart pode
    // aninhar (multipart/alternative dentro de multipart/mixed).
    private String extrairCorpo(JsonNode msgJson) {
        JsonNode payload = msgJson.path("payload");
        String html = buscarParte(payload, "text/html");
        if (html != null) return html;
        return buscarParte(payload, "text/plain");
    }

    private String buscarParte(JsonNode node, String mimeTypeAlvo) {
        if (node == null || node.isMissingNode()) return null;
        String mimeType = node.path("mimeType").asText("");
        String data = node.at("/body/data").asText(null);
        if (mimeType.equals(mimeTypeAlvo) && data != null) {
            return decodeBase64Url(data);
        }
        for (JsonNode parte : node.path("parts")) {
            String achado = buscarParte(parte, mimeTypeAlvo);
            if (achado != null) return achado;
        }
        return null;
    }

    private String decodeBase64Url(String data) {
        try {
            return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // Padrão dos links de vaga por site — cada um tem um jeito diferente de
    // montar a URL de uma vaga individual, então é uma lista de regex (não
    // dá uma genérica só, um link "linkedin.com/jobs/" sozinho pega até o
    // item de menu "Vagas" do cabeçalho de QUALQUER email da LinkedIn, gera
    // lixo). Melhor esforço pra cada um — nunca testado contra o formato
    // real (não dá sem o inbox de verdade do usuário), pode precisar de
    // ajuste; por isso o card sempre pede confirmação antes de importar.
    // LinkedIn: alerta direto (/jobs/view/<id>) e "vagas similares"
    // (/jobs/collections/.../?currentJobId=<id>) são links diferentes, mas
    // os dois têm "jobs/view" ou "jobs/collections" no caminho.
    private static final Pattern LINK_VAGA_LINKEDIN = Pattern.compile(
            "<a[^>]+href=\"(https://[a-z0-9.]*linkedin\\.com/[^\"]*(?:jobs/view|jobs/collections)[^\"]*)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    // Glassdoor: página de vaga individual passa por /job-listing/ ou pelo
    // link "partner" com jobListingId= na query string.
    private static final Pattern LINK_VAGA_GLASSDOOR = Pattern.compile(
            "<a[^>]+href=\"(https://[a-z0-9.]*glassdoor\\.com(?:\\.br)?/[^\"]*(?:job-listing|jobListingId=)[^\"]*)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG_HTML = Pattern.compile("<[^>]+>");

    List<EmailJobCandidate> extrairVagas(String corpoHtml, String dataEmail, String fonte) {
        List<EmailJobCandidate> resultado = new ArrayList<>();
        for (Pattern padrao : List.of(LINK_VAGA_LINKEDIN, LINK_VAGA_GLASSDOOR)) {
            Matcher m = padrao.matcher(corpoHtml);
            while (m.find()) {
                String url = m.group(1).replace("&amp;", "&");
                String tituloBruto = TAG_HTML.matcher(m.group(2)).replaceAll(" ").trim().replaceAll("\\s+", " ");
                String titulo = tituloBruto.isBlank() ? "Vaga (confira o link)" : tituloBruto;
                // Não dá pra extrair o nome da empresa com confiança do HTML
                // sem testar contra o formato real do email — fica em branco
                // de propósito (o card no frontend mostra "—") em vez de
                // arriscar um valor errado que o usuário poderia importar
                // sem reparar.
                resultado.add(new EmailJobCandidate(titulo, null, url, dataEmail, fonte));
            }
        }
        return resultado;
    }
}
