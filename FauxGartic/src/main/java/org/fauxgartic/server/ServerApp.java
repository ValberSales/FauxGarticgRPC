package org.fauxgartic.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;

public class ServerApp {
    public static void main(String[] args) throws IOException, InterruptedException {
        int porta = 50051;

        Server servidor = ServerBuilder.forPort(porta)
                .addService(new ServicoImpl())
                .build();

        System.out.println("Servidor FauxGartic rodando na porta " + porta);
        servidor.start();
        servidor.awaitTermination();
    }
}