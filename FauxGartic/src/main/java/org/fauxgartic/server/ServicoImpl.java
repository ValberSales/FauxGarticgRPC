package org.fauxgartic.server;

import io.grpc.stub.StreamObserver;
import org.fauxgartic.grpc.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ServicoImpl extends FauxGarticServiceGrpc.FauxGarticServiceImplBase {

    // Dados do jogo
    private final List<String> palavras = Arrays.asList("Gato", "Sol", "Casa", "Arvore", "Carro", "Computador", "Pizza");
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
        responseObserver.onCompleted();

        broadcastChat("SERVER", nome + " entrou na sala!");
    }

    @Override
    public void receberEventos(Jogador request, StreamObserver<EventoDeJogo> responseObserver) {
        // Guarda a conexão para enviar eventos depois
        clientes.put(request.getNome(), responseObserver);
    }

    @Override
    public void enviarAcao(AcaoJogador request, StreamObserver<Vazio> responseObserver) {
        String autor = request.getJogador().getNome();

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
            int index = filaJogadores.indexOf(desenhistaAtual);
            int proximo = (index + 1) % filaJogadores.size();
            desenhistaAtual = filaJogadores.get(proximo);
        }

        Rodada r = Rodada.newBuilder().setNomeDesenhista(desenhistaAtual).setPalavraSecreta(palavraAtual).build();
        broadcastEvento(EventoDeJogo.newBuilder().setMudancaRodada(r).build());
        broadcastEvento(EventoDeJogo.newBuilder().setLimparTela(true).build());
        broadcastChat("SERVER", "--- Nova Rodada! Desenhista: " + desenhistaAtual + " ---");
    }

    private void sortearPalavra() {
        palavraAtual = palavras.get(new Random().nextInt(palavras.size()));
    }

    private void broadcastChat(String autor, String msg) {
        String txt = autor.equals("SERVER") ? msg : (autor + ": " + msg);
        broadcastEvento(EventoDeJogo.newBuilder().setMensagemChat(txt).build());
    }

    private void broadcastEvento(EventoDeJogo evt) {
        for (StreamObserver<EventoDeJogo> obs : clientes.values()) {
            try {
                // Sincronizado para evitar envio simultâneo na mesma stream
                synchronized (obs) { obs.onNext(evt); }
            } catch (Exception e) { /* Cliente caiu */ }
        }
    }
}