package org.fauxgartic.server;

import io.grpc.stub.StreamObserver;
import org.fauxgartic.grpc.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ServicoImpl extends FauxGarticServiceGrpc.FauxGarticServiceImplBase {

    // Dados do jogo
    private final List<String> palavras = Arrays.asList("Gato", "Sol", "Casa", "Arvore", "Carro", "Computador", "Pizza", "Aviao", "Banana", "Bola");
    private final List<String> filaJogadores = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, StreamObserver<EventoDeJogo>> clientes = new ConcurrentHashMap<>();

    private String palavraAtual = "";
    private String desenhistaAtual = "";

    public ServicoImpl() {
        sortearPalavra();
    }

    @Override
    public void entrarNoJogo(Jogador request, StreamObserver<EstadoDoJogo> responseObserver) {
        String nome = request.getNome();

        // Evita nomes duplicados desconectando o anterior se houver
        if (clientes.containsKey(nome)) {
            removerJogador(nome);
        }

        // Adiciona na fila se não existir
        synchronized (filaJogadores) {
            if (!filaJogadores.contains(nome)) {
                filaJogadores.add(nome);
                System.out.println("Novo jogador na fila: " + nome);
                // Se for o único, já vira desenhista
                if (filaJogadores.size() == 1) desenhistaAtual = nome;
            }
        }

        boolean souDesenhista = nome.equals(desenhistaAtual);

        // Responde quem é o desenhista atual
        EstadoDoJogo estado = EstadoDoJogo.newBuilder()
                .setSouODesenhista(souDesenhista)
                .setDesenhistaAtual(desenhistaAtual)
                .setPalavraAtual(souDesenhista ? palavraAtual : "")
                .build();

        responseObserver.onNext(estado);
        responseObserver.onCompleted(); // O entrar é uma chamada unária, fecha logo.

        broadcastChat("SERVER", nome + " entrou na sala!");
    }

    @Override
    public void receberEventos(Jogador request, StreamObserver<EventoDeJogo> responseObserver) {
        // Guarda a conexão para enviar eventos depois
        clientes.put(request.getNome(), responseObserver);
        System.out.println("Stream registrado para: " + request.getNome());
    }

    @Override
    public void enviarAcao(AcaoJogador request, StreamObserver<Vazio> responseObserver) {
        String autor = request.getJogador().getNome();

        // Verifica se o cliente ainda está conectado na nossa lista, senão ignora
        if (!clientes.containsKey(autor)) {
            responseObserver.onNext(Vazio.newBuilder().build());
            responseObserver.onCompleted();
            return;
        }

        // 1. Desenho
        if (request.hasTraco() && autor.equals(desenhistaAtual)) {
            broadcastEvento(EventoDeJogo.newBuilder().setDesenho(request.getTraco()).build());
        }
        // 2. Chat / Palpite
        else if (request.hasPalpite()) {
            String chute = request.getPalpite();
            if (!autor.equals(desenhistaAtual) && chute.equalsIgnoreCase(palavraAtual)) {
                broadcastChat("SERVER", ">>> " + autor + " ACERTOU! A palavra era " + palavraAtual);
                proximaRodada();
            } else {
                broadcastChat(autor, chute);
            }
        }
        // 3. Limpar Tela
        else if (request.getLimparTela() && autor.equals(desenhistaAtual)) {
            broadcastEvento(EventoDeJogo.newBuilder().setLimparTela(true).build());
        }

        responseObserver.onNext(Vazio.newBuilder().build());
        responseObserver.onCompleted();
    }

    private void proximaRodada() {
        sortearPalavra();

        synchronized (filaJogadores) {
            if (filaJogadores.isEmpty()) return;

            // Remove jogadores fantasmas da fila antes de sortear
            filaJogadores.removeIf(p -> !clientes.containsKey(p));
            if (filaJogadores.isEmpty()) return;

            int index = filaJogadores.indexOf(desenhistaAtual);
            int proximo = (index + 1) % filaJogadores.size();
            desenhistaAtual = filaJogadores.get(proximo);
        }

        Rodada r = Rodada.newBuilder().setNomeDesenhista(desenhistaAtual).setPalavraSecreta(palavraAtual).build();
        broadcastEvento(EventoDeJogo.newBuilder().setMudancaRodada(r).build());
        broadcastEvento(EventoDeJogo.newBuilder().setLimparTela(true).build());
        broadcastChat("SERVER", "--- Nova Rodada! Desenhista: " + desenhistaAtual + " ---");
    }

    private void removerJogador(String nome) {
        // Remove do mapa de conexões
        StreamObserver<EventoDeJogo> removido = clientes.remove(nome);

        if (removido != null) {
            System.out.println("Jogador desconectado detectado: " + nome);

            // Remove da fila circular
            synchronized (filaJogadores) {
                filaJogadores.remove(nome);
            }

            broadcastChat("SERVER", nome + " saiu do jogo.");

            // Se quem saiu era o desenhista, passa a vez imediatamente
            if (nome.equals(desenhistaAtual)) {
                broadcastChat("SERVER", "O desenhista saiu! Passando a vez...");
                proximaRodada();
            }
        }
    }

    private void sortearPalavra() {
        palavraAtual = palavras.get(new Random().nextInt(palavras.size()));
    }

    private void broadcastChat(String autor, String msg) {
        String txt = autor.equals("SERVER") ? msg : (autor + ": " + msg);
        broadcastEvento(EventoDeJogo.newBuilder().setMensagemChat(txt).build());
    }

    private void broadcastEvento(EventoDeJogo evt) {
        for (Map.Entry<String, StreamObserver<EventoDeJogo>> entry : clientes.entrySet()) {
            String nome = entry.getKey();
            StreamObserver<EventoDeJogo> obs = entry.getValue();

            try {
                // Sincronizado para evitar envio simultâneo na mesma stream
                synchronized (obs) { obs.onNext(evt); }
            } catch (Exception e) {
                removerJogador(nome);
            }
        }
    }
}