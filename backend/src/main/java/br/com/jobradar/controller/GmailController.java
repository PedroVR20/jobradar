package br.com.jobradar.controller;

import br.com.jobradar.service.GmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Integração com Gmail (só-leitura) — ver {@link GmailService} pra detalhes
 * do fluxo OAuth e da extração de vagas dos emails da LinkedIn.
 */
@RestController
@RequestMapping("/api/gmail")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class GmailController {

    private final GmailService gmailService;

    @Value("${google.oauth.frontend-origin:http://localhost:3000}")
    private String frontendOrigin;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        GmailService.Status s = gmailService.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", s.configured());
        body.put("connected", s.connected());
        body.put("email", s.email());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/auth-url")
    public ResponseEntity<Map<String, Object>> authUrl() {
        if (!gmailService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of("error",
                    "Integração com Gmail não configurada — defina GOOGLE_OAUTH_CLIENT_ID/SECRET no .env"));
        }
        return ResponseEntity.ok(Map.of("url", gmailService.buildAuthorizationUrl()));
    }

    // O Google redireciona o NAVEGADOR pra cá (não é uma chamada fetch do
    // frontend) — por isso devolve um 302 de volta pro frontend em vez de um
    // JSON, senão o usuário ficaria vendo um JSON cru na tela no meio do
    // fluxo de login.
    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error) {
        boolean sucesso = false;
        if (error != null) {
            log.warn("Gmail OAuth: usuário negou ou o Google devolveu erro: {}", error);
        } else if (code != null) {
            sucesso = gmailService.handleCallback(code);
        }
        String destino = frontendOrigin + "/?gmail=" + (sucesso ? "conectado" : "erro");
        return ResponseEntity.status(302).location(URI.create(destino)).build();
    }

    @PostMapping("/disconnect")
    public ResponseEntity<Void> disconnect() {
        gmailService.disconnect();
        return ResponseEntity.noContent().build();
    }

    // Mesmo dado que a ferramenta do Hunter usa — exposto direto também pra
    // Configurações poder oferecer um botão "verificar agora" sem precisar
    // passar pelo chat.
    @GetMapping("/vagas-email")
    public ResponseEntity<Map<String, Object>> vagasEmail(
            @RequestParam(required = false, defaultValue = "7") int dias) {
        GmailService.BuscaResultado resultado = gmailService.buscarVagasNosEmails(dias);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conectado", resultado.conectado());
        body.put("vagas", resultado.vagas());
        if (resultado.erro() != null) body.put("erro", resultado.erro());
        return ResponseEntity.ok(body);
    }
}
