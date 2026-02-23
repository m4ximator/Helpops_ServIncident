package mcpr.helpops_serveurIncident;

import java.rmi.Naming;

public class Main {
  public static void main(String[] args) {
    try {
      // 1. Creation objet
      ServIncident ticketService = new ServIncident();

      // Étape 2 : Inscription dans l'annuaire RMI partagé avec Auth
      Naming.rebind("rmi://localhost:1099/TicketService", ticketService);

      System.out.println("Le Serveur d'Incidents est déclaré et prêt !");
      System.out.println("Connecté au Serveur d'Authentification avec succès.");

    } catch (Exception e) {
      System.err.println("Erreur au démarrage du Serveur d'Incidents !");
      e.printStackTrace();
    }
  }
}