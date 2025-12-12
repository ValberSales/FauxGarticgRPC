package org.fauxgartic.server;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.fauxgartic.grpc.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ServicoImpl extends FauxGarticServiceGrpc.FauxGarticServiceImplBase {

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

        if (clientes.containsKey(nome)) {
            removerJogador(nome);
        }

        synchronized (filaJogadores) {
            if (!filaJogadores.contains(nome)) {
                filaJogadores.add(nome);
                if (filaJogadores.size() == 1) desenhistaAtual = nome;
            }
        }

        boolean souDesenhista = nome.equals(desenhistaAtual);

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
        String nome = request.getNome();
        clientes.put(nome, responseObserver);

        // --- DETECÇÃO DE DESCONEXÃO ---

        ServerCallStreamObserver<EventoDeJogo> serverObserver =
                (ServerCallStreamObserver<EventoDeJogo>) responseObserver;

        // Se o cliente fechar a janela ou a net cair, isso roda automaticamente
        serverObserver.setOnCancelHandler(() -> {
            System.out.println("Disconnection detected for: " + nome);
            removerJogador(nome);
        });

        System.out.println("Stream registrado para: " + nome);
    }

    @Override
    public void enviarAcao(AcaoJogador request, StreamObserver<Vazio> responseObserver) {
        String autor = request.getJogador().getNome();

        if (!clientes.containsKey(autor)) {
            responseObserver.onNext(Vazio.newBuilder().build());
            responseObserver.onCompleted();
            return;
        }

        if (request.hasTraco() && autor.equals(desenhistaAtual)) {
            broadcastEvento(EventoDeJogo.newBuilder().setDesenho(request.getTraco()).build());
        }
        else if (request.hasPalpite()) {
            String chute = request.getPalpite();
            if (!autor.equals(desenhistaAtual) && chute.equalsIgnoreCase(palavraAtual)) {
                broadcastChat("SERVER", ">>> " + autor + " ACERTOU! A palavra era " + palavraAtual);
                proximaRodada();
            } else {
                broadcastChat(autor, chute);
            }
        }
        else if (request.getLimparTela() && autor.equals(desenhistaAtual)) {
            broadcastEvento(EventoDeJogo.newBuilder().setLimparTela(true).build());
        }

        responseObserver.onNext(Vazio.newBuilder().build());
        responseObserver.onCompleted();
    }

    private void proximaRodada() {
        sortearPalavra();

        synchronized (filaJogadores) {
            filaJogadores.removeIf(p -> !clientes.containsKey(p));

            if (filaJogadores.isEmpty()) return;

            int index = filaJogadores.indexOf(desenhistaAtual);
            if (index == -1) index = 0;
            else index = (index + 1) % filaJogadores.size();

            desenhistaAtual = filaJogadores.get(index);
        }

        Rodada r = Rodada.newBuilder().setNomeDesenhista(desenhistaAtual).setPalavraSecreta(palavraAtual).build();
        broadcastEvento(EventoDeJogo.newBuilder().setMudancaRodada(r).build());
        broadcastEvento(EventoDeJogo.newBuilder().setLimparTela(true).build());
        broadcastChat("SERVER", "--- Nova Rodada! Desenhista: " + desenhistaAtual + " ---");
    }

    private void removerJogador(String nome) {

        StreamObserver<EventoDeJogo> removido = clientes.remove(nome);

        if (removido != null) {
            System.out.println("Removendo jogador: " + nome);
            synchronized (filaJogadores) {
                filaJogadores.remove(nome);
            }
            broadcastChat("SERVER", nome + " saiu do jogo.");

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

        Set<String> nomes = new HashSet<>(clientes.keySet());

        for (String nome : nomes) {
            StreamObserver<EventoDeJogo> obs = clientes.get(nome);
            if (obs == null) continue;

            try {
                synchronized (obs) { obs.onNext(evt); }
            } catch (Exception e) {
                removerJogador(nome);
            }
        }
    }
}