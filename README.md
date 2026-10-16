# Mini autorizador

[![Build e testes](https://github.com/AdrielDev/mini-autorizador/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/AdrielDev/mini-autorizador/actions/workflows/ci.yml)

API REST para cadastrar cartões, consultar saldo e autorizar compras.
Cada cartão começa com R$ 500,00. Uma compra aprovada debita o saldo;
uma recusa não o altera.

**Stack:** Java 17, Spring Boot 4.1, Spring JDBC, Spring Security,
Flyway e MySQL 5.7.

O enunciado original está em [DESAFIO-VR.md](DESAFIO-VR.md).

[Execução](#como-executar) · [API](#api) · [Testes](#testes) ·
[Arquitetura](#arquitetura) · [Decisões](#decisões-de-implementação) ·
[Observabilidade](#observabilidade) · [Evolução](#evolução) ·
[Limites](#suposições-e-limites)

## Como executar

### Pré-requisitos

| Requisito | Finalidade |
|---|---|
| JDK 17 | Compilar e executar a aplicação |
| Docker com containers Linux | Executar o MySQL e os testes de integração |
| Docker Compose | Iniciar o banco fornecido no desafio |
| Portas 3306 e 8080 disponíveis | MySQL e API, respectivamente |

O projeto inclui o Maven Wrapper. Não é necessário instalar o Maven.
Execute os comandos na raiz do repositório.

### Windows com PowerShell

**1. Inicie o banco:**

```powershell
docker compose -f docker\docker-compose.yml up -d mysql
```

**2. Confira se o MySQL está pronto:**

```powershell
docker compose -f docker\docker-compose.yml logs mysql
```

Aguarde a mensagem `ready for connections` do servidor na porta 3306.

**3. Inicie a API:**

```powershell
.\mvnw.cmd spring-boot:run
```

<details>
<summary><strong>Comandos equivalentes no Linux</strong></summary>

Inicie o banco e consulte os logs:

```bash
docker compose -f docker/docker-compose.yml up -d mysql
docker compose -f docker/docker-compose.yml logs mysql
```

Após o MySQL ficar pronto:

```bash
./mvnw spring-boot:run
```

</details>

### Configuração local

| Serviço | Endereço | Acesso |
|---|---|---|
| API | `http://localhost:8080` | HTTP Basic: `username` / `password` |
| MySQL | `localhost:3306/miniautorizador` | Usuário `root`, senha vazia |

O Flyway aplica as migrations na inicialização. Não é necessário criar
a tabela manualmente.

As credenciais do banco seguem o Compose fornecido no desafio.
São configurações para desenvolvimento local, não para produção.

## API

Todas as operações exigem HTTP Basic.
Os POSTs recebem `Content-Type: application/json`.

A credencial da API autentica a chamada. A senha do cartão é verificada
separadamente para autorizar a compra.

| Método | Endpoint | Sucesso | Recusa ou ausência |
|---|---|---|---|
| `POST` | `/cartoes` | `201`, JSON com os dados recebidos | `422`, cartão já cadastrado |
| `GET` | `/cartoes/{numeroCartao}` | `200`, saldo numérico | `404`, sem corpo |
| `POST` | `/transacoes` | `201`, texto `OK` | `422`, motivo em texto puro |

### Cadastrar cartão

```json
{
  "numeroCartao": "6549873025634501",
  "senha": "1234"
}
```

As respostas `201` e `422` contêm `numeroCartao` e `senha` enviados
naquela tentativa. Um cadastro duplicado não altera os dados existentes.

O retorno da senha segue o contrato do desafio. Não é uma recuperação
da senha persistida, que fica armazenada somente como hash.

### Consultar saldo

```http
GET /cartoes/6549873025634501
```

Exemplo do corpo da resposta para um cartão recém-criado:

```json
500.00
```

O saldo é um número JSON, sem objeto ou aspas.

### Autorizar compra

```json
{
  "numeroCartao": "6549873025634501",
  "senhaCartao": "1234",
  "valor": 10.00
}
```

Com entrada válida, a prioridade das verificações é:
**existência do cartão, senha e saldo disponível**.

| Status | Corpo da resposta (`text/plain`) |
|---|---|
| `201` | `OK` |
| `422` | `CARTAO_INEXISTENTE` |
| `422` | `SENHA_INVALIDA` |
| `422` | `SALDO_INSUFICIENTE` |

### Autenticação e falhas

Credenciais da API ausentes ou incorretas resultam em `401`.

Entradas inválidas e falhas tratadas de persistência retornam JSON
com os campos `codigo` e `mensagem`:

| Status | Código | Mensagem |
|---|---|---|
| `400` | `REQUISICAO_INVALIDA` | `Verifique os campos enviados.` |
| `503` | `ERRO_PERSISTENCIA` | `Não foi possível concluir a operação.` |

Falhas técnicas não são convertidas em aprovação ou saldo insuficiente.

### Roteiro manual

O arquivo [requests.http](requests.http) contém as chamadas na ordem.

Use um número de cartão ainda não cadastrado. Caso o primeiro cadastro
retorne `422`, escolha outro número antes de seguir o roteiro.

| Verificação | Resultado esperado |
|---|---|
| Cadastro e consulta inicial | `201` no cadastro e saldo de `500.00` na consulta |
| Cadastro duplicado | `422`, sem alterar o cartão |
| Compra de `4.85` e consulta | `201` e saldo de `495.15` |
| Compra de `495.15` e consulta | `201` e saldo de `0.00` |
| Tentativas recusadas | Saldo permanece em `0.00` |

O roteiro também inclui autenticação, entrada inválida e Actuator.

## Testes

| Escopo | PowerShell | Linux | Docker |
|---|---|---|---|
| Unitários e controllers | `.\mvnw.cmd test` | `./mvnw test` | Não |
| Suíte completa e cobertura | `.\mvnw.cmd clean verify` | `./mvnw clean verify` | Sim |

Por padrão, Testcontainers inicia um MySQL 5.7.44 descartável.
Os testes não dependem do Compose nem alteram o banco da execução manual.

| Camada ou cenário | O que verifica |
|---|---|
| Unitários | Regras, precisão monetária, prioridade das recusas e propagação de falhas |
| MVC | Contratos HTTP, autenticação e validação |
| Integração com MySQL | Migrations, persistência, duplicidade, hash com salt e débito |
| Rollback | Saldo preservado após falha provocada depois do UPDATE real |
| Concorrência | Aprovações, recusas e saldo final sob chamadas simultâneas |
| Duas instâncias | Dois processos Java disputando o saldo e persistência após reinício |
| Observabilidade | Identificação de requisições, proteção dos logs e acesso ao Actuator |

Surefire executa os testes `*Test`. Failsafe executa os testes `*IT`
no ciclo de integração, e JaCoCo gera a cobertura consolidada em `verify`.

A suíte é sequencial. Os cenários concorrentes coordenam suas próprias
tarefas e verificam os resultados de todas elas.

### Relatórios e CI

Após `clean verify`:

| Caminho | Conteúdo |
|---|---|
| `target\surefire-reports` | Testes unitários e MVC |
| `target\failsafe-reports` | Testes de integração |
| `target\site\jacoco\index.html` | Cobertura consolidada |
| `target\instance-*.log` | Logs dos processos do teste com duas instâncias |

O pipeline cobre integração contínua (CI), sem deploy automatizado (CD).

## Arquitetura

Aplicação única, em camadas e organizada por funcionalidade.

```mermaid
flowchart LR
    Cliente["Cliente HTTP"] --> Filtro["RequestLoggingFilter"]
    Filtro --> Seguranca["Spring Security"]
    Seguranca --> Cartoes["CartaoController"]
    Seguranca --> Compras["TransacaoController"]
    Seguranca --> Actuator["Actuator"]
    Cartoes --> Cadastro["CartaoService"]
    Compras --> Autorizacao["AutorizacaoService"]
    Cadastro --> Repositorio["JdbcCartaoRepository"]
    Autorizacao --> Repositorio
    Repositorio --> Banco[("MySQL 5.7")]
```

| Componente | Responsabilidade |
|---|---|
| Controller | Entrada, saída e status HTTP |
| Service | Regras de negócio e coordenação da operação |
| Repository | SQL e mapeamento dos dados |
| `shared` | Identificação de requisições, logs HTTP e tratamento de erros |
| `config` | Segurança e configuração do encoder |

A separação aplica responsabilidade única (SRP). Os serviços dependem
da interface `CartaoRepository`, não diretamente da implementação JDBC,
aplicando inversão de dependência (DIP).

A injeção por construtor deixa os colaboradores explícitos e permite
testar as regras isoladamente.

### Modelo de dados

A tabela `cartoes` utiliza InnoDB:

| Coluna | Tipo | Restrição |
|---|---|---|
| `numero_cartao` | `VARCHAR(16)` | Chave primária, não nulo |
| `senha_hash` | `VARCHAR(255)` | Não nulo |
| `saldo` | `DECIMAL(19, 2)` | Não nulo, sem DEFAULT |

Não há tabela de compras. O enunciado exige persistir o cartão e
atualizar o saldo.

## Decisões de implementação

<details>
<summary><strong>Concorrência: débito atômico no banco</strong></summary>

Com R$ 10,00 de saldo e duas compras simultâneas de R$ 10,00,
o resultado esperado é uma aprovação, uma recusa e saldo final zerado.

A verificação do saldo e o débito acontecem no mesmo comando:

```sql
UPDATE cartoes
SET saldo = saldo - :valor
WHERE numero_cartao = :numero
  AND saldo >= :valor;
```

Após validar existência e senha:

| Linhas alteradas | Resultado |
|---|---|
| `1` | Aprovação |
| `0` | Saldo insuficiente |
| Qualquer outro valor | Falha técnica |

| Estratégia | Motivo da decisão |
|---|---|
| UPDATE condicional, adotado | A regra cabe no comando, sem calcular e sobrescrever o saldo em Java |
| Bloqueio pessimista explícito | `SELECT ... FOR UPDATE` anteciparia o lock; seria útil para decisões com leituras e alterações coordenadas |
| Controle otimista por versão | Acrescentaria versionamento e tratamento de conflitos; conflito de versão não equivale a saldo insuficiente |

O InnoDB utiliza locks de linha durante o UPDATE. A proteção funciona
entre instâncias que compartilham o banco, sem sincronização na JVM.

`@Transactional` delimita a autorização. O teste de rollback provoca
uma falha após o UPDATE e verifica a preservação do saldo.

</details>

<details>
<summary><strong>Persistência: por que MySQL e JDBC?</strong></summary>

O modelo tem estrutura fixa, identificador único e uma regra monetária
que pode ser expressa diretamente em SQL.

| Escolha | Justificativa |
|---|---|
| MySQL com InnoDB | Débito condicional, unicidade e transações na infraestrutura fornecida |
| Spring JDBC | Três comandos SQL para uma tabela, sem necessidade de gerenciar entidades de um ORM |
| Chave primária | Protege cadastros concorrentes; uma consulta prévia, sozinha, não garante unicidade |
| Tratamento específico de duplicidade | Somente `DuplicateKeyException` representa cartão já cadastrado |
| `BigDecimal` e `DECIMAL` | Precisão monetária sem ponto flutuante ou arredondamento silencioso |
| Saldo inicial no serviço | Regra de cadastro centralizada no código |
| Flyway | Schema versionado e aplicado de forma reproduzível |

MongoDB também permitiria um débito atômico em um único documento,
com filtro de saldo e incremento negativo. Não foi descartado por
falta de atomicidade.

Para alterações atômicas em documentos separados, seria necessário
avaliar transações multidocumento e uma topologia compatível, como
replica set, ausente no Compose fornecido.

Se o domínio crescer em relacionamentos e operações de persistência,
avaliaria Spring Data JDBC ou JPA pelo ganho de produtividade e custo
do mapeamento. ORM não elimina a necessidade de controle concorrente;
atualizações em lote também exigem cuidado com o contexto de persistência.

Alterar o saldo inicial afetaria novos cartões. Ajustar saldos existentes
seria uma operação de negócio separada.

MySQL 5.7 foi mantido conforme o desafio. Uma implantação real exigiria
revisar versões suportadas e requisitos de segurança aplicáveis.

</details>

<details>
<summary><strong>Segurança: Basic Auth e hash de senha</strong></summary>

| Tema | Implementação atual | Evolução para produção |
|---|---|---|
| Autenticação | HTTP Basic stateless, conforme o contrato | HTTPS, gestão e rotação de credenciais e limitação de tentativas |
| Senha do cartão | PBKDF2 com salt pelo Spring Security | Revisar custo, medir latência e planejar migração dos hashes |
| Acesso operacional | Actuator autenticado com a credencial da API | Segregar por perfil ou rede |

PBKDF2 aproveita uma implementação mantida pela biblioteca, sem
criptografia própria. Para um novo serviço, avaliaria Argon2id conforme
as [recomendações da OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).

Entre sistemas, OAuth2 Client Credentials permitiria tokens de curta
duração e escopos de acesso. A validação depende do formato adotado:
JWT exige verificação de assinatura e claims; tokens opacos podem
exigir introspecção no servidor de autorização.

</details>

<details>
<summary><strong>Opcional: fontes Java sem if</strong></summary>

Não há instruções `if`, `break` ou `continue` nos fontes Java de produção
e testes. Dependências, código gerado e Maven Wrapper ficam fora do escopo.

| Recurso | Aplicação |
|---|---|
| `Optional` | Presença, ausência e validação |
| `switch` | Resultados finitos com significado definido |
| Polimorfismo | Comportamento de conclusão ou falha dos logs |
| Awaitility | Espera limitada pela inicialização das instâncias |
| `AutoCloseable` | Encerramento dos processos com try-with-resources |

A restrição atende ao exercício. Não é uma recomendação de eliminar
todo `if` de aplicações Java.

</details>

## Observabilidade

| Recurso | Comportamento |
|---|---|
| `X-Request-ID` | UUID gerado no servidor e devolvido na resposta |
| MDC | Associa o ID aos logs durante o processamento síncrono e restaura o contexto ao terminar |
| Log HTTP | Método, rota parametrizada, status e duração |
| Log de autorização | Resultado da operação, sem dados do cartão |
| Falha não tratada | Evento de erro sem inferir um status que o container ainda definirá |

MDC é o contexto de diagnóstico usado pelo logger. O ID permite localizar
os eventos da mesma requisição, mas não representa tracing distribuído.

Os eventos adicionados pela aplicação não registram corpos, credenciais,
hashes ou números de cartão. A rota de consulta é registrada pelo padrão
`/cartoes/{numeroCartao}`, não pelo identificador concreto.

Identificadores enviados pelo cliente não são reutilizados.
Request IDs e números de cartão não são utilizados como tags de métricas.

### Endpoints operacionais

Todos exigem HTTP Basic:

| Endpoint | Finalidade |
|---|---|
| `/actuator/health` | Saúde da aplicação e do banco, sem detalhes dos componentes |
| `/actuator/metrics/jvm.memory.used` | Uso de memória da JVM |
| `/actuator/metrics/http.server.requests` | Contagem e tempos acumulados das requisições HTTP |

### Diagnóstico inicial

| Sintoma | Primeira investigação |
|---|---|
| `401` | Credenciais da API, não a senha do cartão |
| `422` | Motivo da recusa; não confundir regra de negócio com indisponibilidade |
| `503` | `X-Request-ID`, tipo da falha nos logs, conexão e saúde do banco |
| Latência elevada | Tempo HTTP, CPU, custo das senhas, pool de conexões e espera por locks |

## Evolução

As propostas desta seção não estão implementadas. Sua adoção dependeria
de requisitos, medições e capacidade operacional do time.

| Necessidade | Decisão a avaliar |
|---|---|
| Mais tráfego | Medir os gargalos antes de escalar; mais instâncias não eliminam contenção sobre o mesmo cartão |
| Auditoria e reconciliação | Modelar histórico financeiro, acesso e retenção; logs operacionais não substituem esse registro |
| Mais consumidores da API | Avaliar OpenAPI, compatibilidade e versionamento |
| Operação em produção | Centralizar métricas e definir SLOs e alertas |

SLOs são metas de nível de serviço, como disponibilidade e latência.
Elas orientam monitoramento e resposta a incidentes.

### Reenvio de compras e idempotência

**Comportamento atual:** cada POST válido é tratado como uma nova compra.
Se a resposta se perder depois do commit, reenviar a chamada pode gerar
outro débito.

A proposta seria aceitar uma chave opcional criada pelo cliente para
identificar a operação. O cliente reutilizaria a mesma chave nos reenvios.

Exemplo de contrato futuro, ainda não suportado:

```http
POST /transacoes
Authorization: Basic dXNlcm5hbWU6cGFzc3dvcmQ=
Idempotency-Key: compra-001
Content-Type: application/json

{
  "numeroCartao": "6549873025634501",
  "senhaCartao": "1234",
  "valor": 10.00
}
```

Partindo de R$ 500,00:

| Situação | Resposta proposta | Saldo |
|---|---|---|
| Primeiro envio com `compra-001` | `201 OK` | `490.00` |
| Reenvio da mesma chave e dos mesmos dados | Reproduz o resultado anterior | `490.00` |
| Mesma chave com outro valor ou cartão | `409 Conflict` | `490.00` |
| Nova chave para outra compra de R$ 10,00 | `201 OK` | `480.00` |

Sem o header, o comportamento atual seria preservado.

Para funcionar entre instâncias, a reserva da chave, o resultado e o débito
precisariam ser coordenados na mesma transação, com proteção de unicidade.
Uma falha antes do commit reverteria os registros envolvidos; após o commit,
o reenvio recuperaria o resultado persistido.

O contrato também precisaria definir o escopo por consumidor, a validade
da chave e a comparação segura dos dados, sem armazenar a senha original.

Compras distintas podem ter o mesmo cartão, valor e horários próximos.
A chave identifica a operação, não uma semelhança entre requisições.

<details>
<summary><strong>Mensageria: Kafka ou RabbitMQ</strong></summary>

Fariam sentido para notificações e integrações após a compra, retirando
processamento secundário do caminho da resposta HTTP.

| Tecnologia | Cenário a avaliar |
|---|---|
| Kafka | Streams de eventos com retenção, replay e múltiplos consumidores |
| RabbitMQ | Filas de trabalho com roteamento e distribuição de tarefas |

A escolha dependeria de volume, ordenação, reprocessamento e operação.
A autorização continuaria síncrona.

Para evitar atualizar o saldo e perder a publicação do evento,
avaliaria Transactional Outbox: gravar evento e débito na mesma transação
e publicar depois.

A entrega pode se repetir. O consumidor precisaria controlar eventos
já processados para não executar novamente o mesmo efeito de negócio.

</details>

<details>
<summary><strong>Resiliência em integrações HTTP</strong></summary>

Em uma futura integração externa, como antifraude, avaliaria:

| Mecanismo | Finalidade |
|---|---|
| Timeout | Limitar o tempo de espera por uma chamada |
| Circuit Breaker | Suspender chamadas após falhas recorrentes e testar a recuperação da dependência |
| Bulkhead | Limitar a concorrência para uma dependência, evitando esgotar os recursos da aplicação |
| Retry com backoff | Repetir falhas transitórias com limite de tentativas e intervalos crescentes, somente em operações seguras para repetição |

Não há integração HTTP externa que justifique esses componentes hoje.
Circuit Breaker não resolve concorrência no saldo nem substitui timeout.

Recusas de negócio não seriam contabilizadas como indisponibilidade.
Um fallback nunca poderia aprovar sem validar as regras ou transformar
falha técnica em saldo insuficiente.

</details>

<details>
<summary><strong>Cloud, containers e microsserviços</strong></summary>

Docker é utilizado no banco local e nos testes. A entrega não inclui
imagem da aplicação, Kubernetes ou deploy automatizado.

Para implantação, avaliaria imagem da API, limites de recursos,
encerramento gracioso, backup, recuperação e probes:

| Probe | Pergunta |
|---|---|
| Readiness | A instância está apta a receber tráfego? |
| Liveness | O processo precisa ser reiniciado? |

Dependências críticas podem influenciar readiness. Uma falha externa
não deveria, por si só, causar reinícios por liveness.

Microsserviços seriam considerados diante de fronteiras de domínio
e necessidade de implantação ou escala independente. Crescimento
de tráfego, sozinho, não justifica distribuir o sistema.

</details>

## Suposições e limites

| Item | Regra adotada |
|---|---|
| Número do cartão | String com exatamente 16 dígitos ASCII; `VARCHAR(16)` preserva zeros à esquerda |
| Validação do cartão | Sem validação de bandeira ou algoritmo de Luhn |
| Senha | Não pode estar em branco |
| Valor da compra | Positivo, dentro de `DECIMAL(19, 2)` e representável em centavos sem arredondamento |
| Casas decimais | `10.000` é aceito; `10.001` é rejeitado |
| Campos JSON desconhecidos | Ignorados; o cliente não define o saldo inicial |
| Operações fora do escopo | Recarga, exclusão e alteração de senha |

A entrega cobre o enunciado. Não pretende reproduzir todos os controles
operacionais e regulatórios de um autorizador em produção.