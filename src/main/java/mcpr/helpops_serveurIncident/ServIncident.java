package mcpr.helpops_serveurIncident;

import mcpr.hellpops_interfaces.*;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import static mcpr.hellpops_interfaces.Role.*;
import static mcpr.hellpops_interfaces.EtatIncident.*;


public class ServIncident extends UnicastRemoteObject implements ITicketService{
    private final String CHEMIN_FICHIER = "incident.json";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private IAuthService auth;

    // Base de données thread-safe pour les incidents
    private final List<Incident> incidentEnBase = new ArrayList<>();
    private final GestionnaireSupervision gestionnaireSupervision;

    // Compteur thread-safe pour générer les identifiants
    private final AtomicInteger compteurId = new AtomicInteger(0);
    private int nbEnLecture=0;
    private boolean enEcriture=false;


    //Initialise le serveurIncident, et le connecte au serveur d'authentification via RMI
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

        } catch (Exception e) {
            System.err.println("Erreur critique : serveur Auth inateignable.");
        }
        this.gestionnaireSupervision = new GestionnaireSupervision(auth);

    }

    // Permet à un utilisateur ou un agent de créer un incident
    @Override
    public String creerIncident(Jeton jeton, String categorie, String titre, String desc) throws RemoteException {
        // Demande au serveur Auth à qui appartient le jeton
        String loginCreateur = auth.getLoginParJeton(jeton);

        // Si login différent de null, jeton valide
        if (loginCreateur != null){
            debutEcriture();
            int id = compteurId.incrementAndGet();
            Incident incident = new Incident(id, titre, categorie, desc, loginCreateur);
            incidentEnBase.add(incident);
            StringBuilder chaine = new StringBuilder();
            chaine.append("Ticket #").append(id).append(" créé par ").append(loginCreateur);
            System.out.println(chaine);

            //On sauvegarde le nouvel incident dans un json
            sauvegarderDonneesIncident();
            finEcriture();
            gestionnaireSupervision.notifierSuperviseurs(chaine + " (État: OPEN)");
            return chaine.toString();
        }
        return "Session Expirée";
    }

    // Permet à un utilisateur ou à un agent de consulter la liste de ses incidents
    @Override
    public List<Incident> consulterListeIncident(Jeton jeton) throws RemoteException {
        String loginDemandeur = auth.getLoginParJeton(jeton);

        if (loginDemandeur != null) {

            debutLecture();
            //liste vide pour les tickets du client
            List<Incident> ticketsDuClient = new ArrayList<>();

            // Parcours de la liste globale
            for (Incident incident : incidentEnBase) {
                if (incident.getIdentifiantCreateur().equals(loginDemandeur)) {
                    ticketsDuClient.add(incident);
                }
            }
            finLecture();
            return ticketsDuClient; //liste filtrée
        }
        return null; // Jeton invalide
    }


    //Fonction permettant de consulter les détails d'un ticket, en vérifiant que le jeton est valide
    @Override
    public Incident consulterIncidentDetail(Jeton jeton, int id) throws RemoteException {
        debutLecture();
        String loginDemandeur = auth.getLoginParJeton(jeton);

        if (loginDemandeur != null) {
            for (Incident incident : incidentEnBase) {
                if (incident.getIdentifiant() == id && (incident.getIdentifiantCreateur().equals(loginDemandeur) || jeton.getRole() == AGENT)) {
                    finLecture();
                    return incident;
                }
            }
        }
        finLecture();
        return null;
    }

    // Permet à un utilisateur ou un agent de modifier l'un de ses incidents
    @Override
    public Incident modifierIncident(Jeton jeton, int id, String categorie, String titre, String desc) throws RemoteException {
        Incident incidentToModif = consulterIncidentDetail(jeton, id);
        String loginDemandeur = auth.getLoginParJeton(jeton);

        if ((incidentToModif != null && incidentToModif.getIdentifiantCreateur().equals(loginDemandeur))){
            debutEcriture();
            if (categorie != null) {
                incidentToModif.setCategorie(categorie);
            }
            if (titre != null) {
                incidentToModif.setTitre(titre);
            }
            if (desc != null) {
                incidentToModif.setDescription(desc);
            }
            sauvegarderDonneesIncident();
            finEcriture();
            gestionnaireSupervision.notifierSuperviseurs("Ticket #" + id + " mis à jour par " + loginDemandeur);
            return incidentToModif;
        }
        else{
            System.out.println("Un utilisateur essaye de modifier un ticket qui n'est pas sien");
            gestionnaireSupervision.notifierSuperviseurs("ALERTE SÉCURITÉ : L'utilisateur '" + loginDemandeur + "' a tenté de modifier le ticket #" + id + " sans autorisation !");
            return null;
        }

    }

    // Permet à un agent de résoudre un incident lui étant attribué. Renvoie une erreur sinon
    @Override
    public String resoudreIncident(Jeton jeton, int id, String message) throws RemoteException {
        // Vérif agent
        if (!estAgentValide(jeton)) {
            return "Accès refusé, vous n'êtes pas un Agent.";
        }

        String nomAgent = auth.getLoginParJeton(jeton);
        Incident incident = null;
        debutEcriture();

        for (Incident current : incidentEnBase) {
            if (current.getIdentifiant() == id) {
                incident = current;
                break;
            }
        }
        if (incident == null) {
            finEcriture();
            return "Ticket inexistant";
        }
        if (!nomAgent.equals(incident.getAgentResponsable())) {
            finEcriture();
            return "Vous n'êtes pas l'agent responsable du ticket !";
        }
        if (incident.getEtat() != ASSIGNED) {
            finEcriture();
            return "Le ticket ne peut pas être résolu, si il n'est pas assigné";
        }

        // Résolution du ticket il est assigné et que c'est l'agent responsable qui veut le résoudre
        incident.setEtat(RESOLVED);
        incident.setDateResolution(new Date());
        incident.setMessageResolution(message);

        sauvegarderDonneesIncident();
        finEcriture();
        gestionnaireSupervision.notifierSuperviseurs("Ticket #" + id + " résolu par " + nomAgent + " (État: RESOLVED)");
        return "Ticket résolu !";
    }

    // Permet d'attribuer un incident à un agent, en modifiant le statut du ticket et son agent responsable
    @Override
    public String attribuerIncident(Jeton jeton, int id) throws RemoteException {

        // Verification droits
        if (!estAgentValide(jeton)) {
            return "Accès refusé : Vous n'êtes pas un Agent ou session expirée.";
        }
        String nomAgent = auth.getLoginParJeton(jeton);
        debutEcriture();

        Incident attributionIncident = null;
        for (Incident incident : incidentEnBase) {
            if (incident.getIdentifiant() == id) {
                attributionIncident = incident;
                break;
            }
        }
        if (attributionIncident==null){
            return "Id ticket inexistant";
        }
        if (attributionIncident.getEtat() == OPEN) {
            attributionIncident.setAgentResponsable(nomAgent);
            attributionIncident.setEtat(ASSIGNED);
            sauvegarderDonneesIncident();
            finEcriture();
            gestionnaireSupervision.notifierSuperviseurs("Ticket #" + id + " assigné à " + nomAgent + " (État: ASSIGNED)");
            return "Ticket assigné avec succes !";
        }
        finEcriture();
        return "Impossible : Ce ticket est déjà assigné ou résolu.";
    }

    // permet de consulter les incidents de l'agent
    @Override
    public List<Incident> consulterIncidentAgent(Jeton jeton) throws RemoteException{
        debutLecture();
        List<Incident> ticketsAgent = verifRoleAndCreaList(jeton);

        if (ticketsAgent == null) {
            finLecture();
            return null;
        }
        String nomAgent = auth.getLoginParJeton(jeton);
        // Parcours de la liste globale
        for (Incident incident : incidentEnBase) {
            if (incident.getAgentResponsable() != null && incident.getAgentResponsable().equals(nomAgent)) {
                ticketsAgent.add(incident);
            }
        }
        finLecture();
        return ticketsAgent;
    }

    // Permet de consulter les incidents en attente
    @Override
    public List<Incident> consulterIncidentEnAttente(Jeton jeton) throws RemoteException{
        debutLecture();
        //liste vide pour les tickets de l'agent
        List<Incident> ticketsEnAttente= verifRoleAndCreaList(jeton);

        if (ticketsEnAttente == null) {
            finLecture();
            return null;
        }
        // Parcours de la liste globale
        for (Incident incident : incidentEnBase) {
            if (incident.getEtat() == OPEN) {
                ticketsEnAttente.add(incident);
            }
        }
        finLecture();
        return ticketsEnAttente; //liste filtrée
    }

    //Permet de consulter tout les incidents
    @Override
    public List<Incident>  consulterTouslesIncidents (Jeton jeton) throws RemoteException{
        debutLecture();
        List<Incident> tickets = verifRoleAndCreaList(jeton);

        if (tickets == null){
            finLecture();
            return null;
        }
        tickets.addAll(incidentEnBase);
        finLecture();
        return tickets;
    }
    // Sauvegarde les données des incidents contenues dans la liste dans un fichier json
    private synchronized void sauvegarderDonneesIncident() {
        //déclaration dans les parenthèses pour fermeture du fichier automatique
        try (FileWriter writer = new FileWriter(CHEMIN_FICHIER)) {
            //Transformation liste en texte JSON
            gson.toJson(incidentEnBase, writer);
        } catch (Exception e) {
            System.err.println("Erreur lors de la sauvegarde JSON : " + e.getMessage());
        }
    }

    // Charge les tickets à partir d'un fichier json nommé incident.json dans une liste
    // Si le fichier n'existe pas, en crée un
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
    // Vérifie la validité de l'agent et renvoie un boolean
    protected boolean estAgentValide(Jeton jeton) throws RemoteException {
        String nomAgent = auth.getLoginParJeton(jeton);
        Role role = auth.getRoleParJeton(jeton);

        return nomAgent != null && role == AGENT;
    }
    // Vérifie la validité du superviseur et renvoie un boolean
    protected boolean estSuperviseurValide(Jeton jeton) throws RemoteException {
        String nomSuperviseur = auth.getLoginParJeton(jeton);
        Role role = auth.getRoleParJeton(jeton);

        return nomSuperviseur != null && role == SUPERVISEUR;
    }

    // Vérifie que l'agent et valide et crée une liste de ses incidents assignés si oui
    public List<Incident> verifRoleAndCreaList(Jeton jeton) throws RemoteException {
        if (estAgentValide(jeton)) {
            //liste vide pour les tickets de l'agent
            List<Incident> ticketsAgent = new ArrayList<>();
            return ticketsAgent;
        }
        else {
            return null;
        }
    }


    // Partie Lecteur Redacteur

    //Ajoute un lecteur si personne n'est en écriture
    public synchronized void debutLecture() {
        while(enEcriture) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        nbEnLecture++;
    }

    // Enlève un membre de la lecture et notifie les processus en attente
    public synchronized void finLecture() {
        nbEnLecture--;
        if(nbEnLecture==0) {
            this.notify();
        }
    }

    // Passe en écriture si personne n'écrie et personne ne lit
    public synchronized void debutEcriture() {
        while(enEcriture || nbEnLecture>0) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        enEcriture = true;
    }

    // Libère la place et notifie les processus en attente
    public synchronized void finEcriture() {
        enEcriture = false;
        this.notifyAll();
    }


    // Partie Stats via la classe Statistique
    @Override
    public String[] getStatistiques(Jeton jeton) throws RemoteException{

        if (!estAgentValide(jeton)) {
            return null;
        }

        Statistique statistique = new Statistique();
        debutLecture();
        String [] tabStat = statistique.getStat(jeton, incidentEnBase);
        finLecture();
        return tabStat;
    }


    // Partie Appel GestionnaireSupervision
    @Override
    public void abonnerFluxDirect(ISupervision iSupervision, Jeton jeton) throws RemoteException {
        gestionnaireSupervision.abonnerFluxDirect(iSupervision, jeton);
    }

    @Override
    public List<String> abonnerAvecRattrapage(ISupervision iSupervision, Jeton jeton) throws RemoteException {
        return gestionnaireSupervision.abonnerAvecRattrapage(iSupervision, jeton);
    }

}
