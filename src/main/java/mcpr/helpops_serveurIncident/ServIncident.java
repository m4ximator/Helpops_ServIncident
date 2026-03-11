package mcpr.helpops_serveurIncident;

import mcpr.hellpops_interfaces.Incident;
import mcpr.hellpops_interfaces.IAuthService;
import mcpr.hellpops_interfaces.ITicketService;
import mcpr.hellpops_interfaces.Jeton;
import mcpr.hellpops_interfaces.Role;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import static mcpr.hellpops_interfaces.Role.AGENT;

public class ServIncident extends UnicastRemoteObject implements ITicketService{
    private final String CHEMIN_FICHIER = "incident.json";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private IAuthService auth;

    // Base de données thread-safe pour les incidents
    private final List<Incident> incidentEnBase = new CopyOnWriteArrayList<>();

    // Compteur thread-safe pour générer les identifiants
    private final AtomicInteger compteurId = new AtomicInteger(0);

    public ServIncident() throws RemoteException {
        super();
        chargerDonneesIncident();
        int maxId = 0;
        for (Incident incident : incidentEnBase) {
            if (incident.getIdentifiant() > maxId) {
                maxId = incident.getIdentifiant();
            }
        }
        compteurId.set(maxId);
        try {
            auth = (IAuthService) Naming.lookup("rmi://localhost:1099/AuthService");

        }catch (Exception e){
            System.err.println("Erreur critique : serveur Auth inateignable.");
        }
    }

    @Override
    public String creerIncident(Jeton jeton, String categorie, String titre, String desc) throws RemoteException {
        // Demande au serveur Auth à qui appartient le jeton
        String loginCreateur = auth.getLoginParJeton(jeton);

        // Si login différent de null, jeton valide
        if (loginCreateur != null){
            int id = compteurId.incrementAndGet();
            Incident incident = new Incident(id, titre, categorie, desc, loginCreateur);
            incidentEnBase.add(incident);
            StringBuilder chaine = new StringBuilder();
            chaine.append("Ticket #").append(id).append(" créé par ").append(loginCreateur);
            System.out.println(chaine);

            //On sauvegarde le nouvel incident dans un json
            sauvegarderDonneesIncident();
            return chaine.toString();
        }
        return "Session Expirée";
    }

    @Override
    public List<Incident> consulterListeIncident(Jeton jeton) throws RemoteException {
        String loginDemandeur = auth.getLoginParJeton(jeton);

        if (loginDemandeur != null) {
            //liste vide pour les tickets du client
            List<Incident> ticketsDuClient = new ArrayList<>();
            // On met à jours la liste des Incidents via le json

            // Parcours de la liste globale
            for (Incident incident : incidentEnBase) {
                if (incident.getIdentifiantCreateur().equals(loginDemandeur)) {
                    ticketsDuClient.add(incident);
                }
            }
            return ticketsDuClient; //liste filtrée
        }
        return null; // Jeton invalide
    }


    //Fonction permettant de consulter les détails d'un ticket, en vérifiant que le jeton est valide
    @Override
    public Incident consulterIncidentDetail(Jeton jeton, int id) throws RemoteException {
        String loginDemandeur = auth.getLoginParJeton(jeton);

        if (loginDemandeur != null) {
            for (Incident incident : incidentEnBase) {
                if (incident.getIdentifiant() == id && (incident.getIdentifiantCreateur().equals(loginDemandeur) || jeton.getRole() == Role.AGENT)) {
                    return incident;
                }
            }
        }
        return null;
    }

    @Override
    public Incident modifierIncident(Jeton jeton, int id, String categorie, String titre, String desc) throws RemoteException {
        Incident incidentToModif = consulterIncidentDetail(jeton, id);

        if (incidentToModif != null){
            if (categorie != null) {
                incidentToModif.setCategorie(categorie);
            }
            if (titre != null) {
                incidentToModif.setTitre(titre);
            }
            if (desc != null) {
                incidentToModif.setDescription(desc);
            }
        }
        sauvegarderDonneesIncident();
        return incidentToModif;
    }

    @Override
    public String attribuerIncident(Jeton jeton, int id) throws RemoteException {
        String nomAgent = auth.getLoginParJeton(jeton);
        Role role = auth.getRoleParJeton(jeton);

        // Verification droits
        if (nomAgent == null || role != AGENT) {
            return "Accès refusé : Vous n'êtes pas un Agent ou session expirée.";
        }
        Incident attributionIncident = null;
        for (Incident incident : incidentEnBase) {
            if (incident.getIdentifiant() == id) {
                attributionIncident = incident;
                break;
            }
            else{
                return "Id ticket inexistant";
            }
        }

        if ("OPEN".equals(attributionIncident.getEtat())) {
            attributionIncident.setAgentResponsable(nomAgent);
            attributionIncident.setEtat("Assigned");
            sauvegarderDonneesIncident();
            return "Ticket assigné avec succes !";
        }
        return "Impossible : Ce ticket est déjà assigné ou résolu.";
    }

    @Override
    public List<Incident> consulterIncidentAgent(Jeton jeton) throws RemoteException{
        String nomAgent = auth.getLoginParJeton(jeton);
        Role role = auth.getRoleParJeton(jeton);

        if (nomAgent != null && role == AGENT) {
            //liste vide pour les tickets de l'agent
            List<Incident> ticketsAgent= new ArrayList<>();

            // Parcours de la liste globale
            for (Incident incident : incidentEnBase) {
                if (incident.getAgentResponsable() != null && incident.getAgentResponsable().equals(nomAgent)) {
                    ticketsAgent.add(incident);
                }
            }
            return ticketsAgent; //liste filtrée
        }
        return null; // Jeton invalide ou role non agent
    }

    @Override
    public List<Incident> consulterIncidentEnAttente(Jeton jeton) throws RemoteException{
        String nomAgent = auth.getLoginParJeton(jeton);
        Role role = auth.getRoleParJeton(jeton);

        if (nomAgent != null && role == AGENT) {
            //liste vide pour les tickets de l'agent
            List<Incident> ticketsEnAttente= new ArrayList<>();

            // Parcours de la liste globale
            for (Incident incident : incidentEnBase) {
                if ("OPEN".equals(incident.getEtat())) {
                    ticketsEnAttente.add(incident);
                }
            }
            return ticketsEnAttente; //liste filtrée
        }
        return null; // Jeton invalide ou role non agent
    }

    private void sauvegarderDonneesIncident() {
        //déclaration dans les parenthèses pour fermeture du fichier automatique
        try (FileWriter writer = new FileWriter(CHEMIN_FICHIER)) {
            //Transformation liste en texte JSON
            gson.toJson(incidentEnBase, writer);
        } catch (Exception e) {
            System.err.println("Erreur lors de la sauvegarde JSON : " + e.getMessage());
        }
    }

    private void chargerDonneesIncident() {
        File fichier = new File(CHEMIN_FICHIER);
        if (fichier.exists()) {
            try (FileReader reader = new FileReader(fichier)) {
                //Astuce Gson pour lire une liste typée (permet d'instancier le bon type)
                Type typeListe = new TypeToken<List<Incident>>() {
                }.getType();
                List<Incident> usersCharges = gson.fromJson(reader, typeListe);

                if (usersCharges != null) {
                    incidentEnBase.addAll(usersCharges);
                    String chaine = "Base chargée : " +
                            incidentEnBase.size() +
                            " incidents(s).";
                    System.out.println(chaine);
                }

            } catch (Exception e) {
                System.err.println("Impossible de lire le fichier JSON : " + e.getMessage());
            }
        } else {
            System.out.println("Aucun fichier JSON trouvé, démarrage avec une base vide.");
        }
    }
}
