CREATE TABLE cartoes (
    numero_cartao VARCHAR(16)    NOT NULL,
    senha_hash    VARCHAR(255)   NOT NULL,
    saldo         DECIMAL(19, 2) NOT NULL,
    PRIMARY KEY (numero_cartao)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;