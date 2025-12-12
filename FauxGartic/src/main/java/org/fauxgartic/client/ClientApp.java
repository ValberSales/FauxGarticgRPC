package org.fauxgartic.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.fauxgartic.grpc.*;

public class ClientApp extends Application {

    // GUI
    private GraphicsContext gc;
    private TextArea chatArea = new TextArea();
    private TextField inputField = new TextField();
    private Label lblStatus = new Label("Conectando...");

    // Estado
    private String serverIp; // Guarda o IP digitado
    private String meuNome;
    private boolean souDesenhista = false;

    // gRPC
    private FauxGarticServiceGrpc.FauxGarticServiceStub asyncStub;

    @Override
    public void start(Stage stage) {
        // 1. Pede o IP do servidor (Novo passo)
        serverIp = pedirIP();
        if (serverIp == null || serverIp.trim().isEmpty()) return;

        // 2. Pede o Nome
        meuNome = pedirNome();
        if (meuNome == null || meuNome.trim().isEmpty()) return;

        // Layout
        BorderPane root = new BorderPane();

        // Topo
        VBox top = new VBox(lblStatus);
        top.setPadding(new Insets(10));
        top.setStyle("-fx-background-color: #ddd; -fx-font-size: 16px;");
        root.setTop(top);

        // Centro (Canvas)
        Canvas canvas = new Canvas(800, 500);
        gc = canvas.getGraphicsContext2D();
        gc.setLineWidth(3);
        gc.setStroke(Color.BLACK);
        root.setCenter(canvas);

        // Eventos de Mouse
        canvas.setOnMousePressed(e -> {
            if (souDesenhista) {
                desenharLocal(e.getX(), e.getY(), true);
                enviarTraco(e.getX(), e.getY(), true);
            }
        });
        canvas.setOnMouseDragged(e -> {
            if (souDesenhista) {
                desenharLocal(e.getX(), e.getY(), false);
                enviarTraco(e.getX(), e.getY(), false);
            }
        });

        // Chat
        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        inputField.setOnAction(e -> enviarPalpite());
        VBox bottom = new VBox(5, chatArea, inputField);
        bottom.setPadding(new Insets(10));
        root.setBottom(bottom);

        stage.setScene(new Scene(root, 800, 700));
        stage.setTitle("FauxGartic - " + meuNome + " (Conectado em " + serverIp + ")");
        stage.show();

        conectarAoServidor();
    }

    private void conectarAoServidor() {
        // Usa o IP digitado na variável 'serverIp'
        ManagedChannel channel = ManagedChannelBuilder.forAddress(serverIp, 50051)
                .usePlaintext()
                .build();

        var blockingStub = FauxGarticServiceGrpc.newBlockingStub(channel);
        asyncStub = FauxGarticServiceGrpc.newStub(channel);

        new Thread(() -> {
            try {
                // Entra no Jogo
                Jogador eu = Jogador.newBuilder().setNome(meuNome).build();
                EstadoDoJogo estado = blockingStub.entrarNoJogo(eu);

                Platform.runLater(() -> atualizarEstado(estado.getSouODesenhista(), estado.getDesenhistaAtual(), estado.getPalavraAtual()));

                // Escuta eventos
                blockingStub.receberEventos(eu).forEachRemaining(evento -> {
                    Platform.runLater(() -> processarEvento(evento));
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    chatArea.appendText("ERRO FATAL: Não foi possível conectar em " + serverIp + "\n");
                    lblStatus.setText("Desconectado.");
                });
            }
        }).start();
    }

    private void processarEvento(EventoDeJogo evt) {
        if (evt.hasDesenho() && !souDesenhista) {
            Traco t = evt.getDesenho();
            desenharLocal(t.getX(), t.getY(), t.getNovoTraco());
        } else if (evt.hasMensagemChat()) {
            chatArea.appendText(evt.getMensagemChat() + "\n");
        } else if (evt.hasMudancaRodada()) {
            Rodada r = evt.getMudancaRodada();
            atualizarEstado(r.getNomeDesenhista().equals(meuNome), r.getNomeDesenhista(), r.getPalavraSecreta());
        } else if (evt.getLimparTela()) {
            gc.clearRect(0, 0, 800, 500);
        }
    }

    private void atualizarEstado(boolean sou, String desenhista, String palavra) {
        this.souDesenhista = sou;
        if (sou) {
            lblStatus.setText("SUA VEZ! DESENHE: " + palavra);
            lblStatus.setStyle("-fx-background-color: #aaffaa; -fx-padding: 10px;");
            inputField.setDisable(true);
            inputField.setPromptText("Você está desenhando...");
        } else {
            lblStatus.setText("Adivinhe o desenho de " + desenhista);
            lblStatus.setStyle("-fx-background-color: #ddd; -fx-padding: 10px;");
            inputField.setDisable(false);
            inputField.setPromptText("Digite seu palpite...");
        }
    }

    private void enviarTraco(double x, double y, boolean novo) {
        AcaoJogador acao = AcaoJogador.newBuilder()
                .setJogador(Jogador.newBuilder().setNome(meuNome).build())
                .setTraco(Traco.newBuilder().setX(x).setY(y).setNovoTraco(novo).build())
                .build();
        asyncStub.enviarAcao(acao, new StreamObserver<>() {
            @Override public void onNext(Vazio v) {}
            @Override public void onError(Throwable t) {}
            @Override public void onCompleted() {}
        });
    }

    private void enviarPalpite() {
        String texto = inputField.getText();
        if (texto.isEmpty()) return;
        inputField.clear();

        AcaoJogador acao = AcaoJogador.newBuilder()
                .setJogador(Jogador.newBuilder().setNome(meuNome).build())
                .setPalpite(texto)
                .build();
        asyncStub.enviarAcao(acao, new StreamObserver<>() {
            @Override public void onNext(Vazio v) {}
            @Override public void onError(Throwable t) {}
            @Override public void onCompleted() {}
        });
    }

    private void desenharLocal(double x, double y, boolean novo) {
        if (novo) { gc.beginPath(); gc.moveTo(x, y); gc.stroke(); }
        else { gc.lineTo(x, y); gc.stroke(); }
    }

    // --- DIÁLOGOS ---

    private String pedirIP() {
        TextInputDialog dialog = new TextInputDialog("localhost"); // Valor padrão
        dialog.setTitle("Conexão");
        dialog.setHeaderText("Configuração de Rede");
        dialog.setContentText("Digite o IP do servidor:");
        return dialog.showAndWait().orElse(null);
    }

    private String pedirNome() {
        TextInputDialog dialog = new TextInputDialog("Jogador");
        dialog.setTitle("Login");
        dialog.setHeaderText("Bem-vindo ao FauxGartic");
        dialog.setContentText("Seu nome:");
        return dialog.showAndWait().orElse(null);
    }

    public static void main(String[] args) { launch(args); }
}