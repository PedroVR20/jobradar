package br.com.jobradar.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

// Singleton (id sempre 1) — Job Radar é um app pessoal de um usuário só, sem
// sistema de auth (ver "o que NÃO copiar" documentado nos outros services),
// então não faz sentido modelar isso como tabela multi-usuário. Guarda o
// refresh token OAuth do Gmail (não expira, usado pra pedir um access token
// novo sempre que precisar) + o access token atual + validade, pra não
// precisar repetir o consentimento do usuário a cada requisição.
@Entity
@Table(name = "gmail_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GmailToken {

    @Id
    private Long id; // sempre 1L

    @Column(columnDefinition = "TEXT")
    private String accessToken;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String refreshToken;

    private LocalDateTime accessTokenExpiresAt;

    // E-mail da conta conectada, só pra exibir em Configurações ("Conectado
    // como fulano@gmail.com") — não usado em nenhuma lógica.
    private String emailAddress;

    private LocalDateTime connectedAt;
}
