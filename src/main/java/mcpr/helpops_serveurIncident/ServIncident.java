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
    private int nbEnLecture=0;
    private boolean enEcriture=false;

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

    }

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
        debutLecture();
        String loginDemandeur = auth.getLoginParJeton(jeton);

        if (loginDemandeur != null) {
            for (Incident incident : incidentEnBase) {
                if (incident.getIdentifiant() == id && (incident.getIdentifiantCreateur().equals(loginDemandeur) || jeton.getRole() == Role.AGENT)) {
                    finLecture();
                    return incident;
                }
            }
        }
        finLecture();
        return null;
    }

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
            return incidentToModif;
        }
        else{
            System.out.println("Un utilisateur essaye de modifier un ticket qui n'est pas sien");
            return null;
        }

    }

    @Override
    public synchronized String resoudreIncident(Jeton jeton, int id, String message) throws RemoteException {

        // Vérif agent
        if (!estAgentValide(jeton)) {
            return "Accès refusé, vous n'êtes pas un Agent.";
        }

        String nomAgent = auth.getLoginParJeton(jeton);
        Incident incident = null;

        for (Incident incident1 : incidentEnBase) {
            if (incident1.getIdentifiant() == id) {
                incident = incident1;
                break;
            }
        }

        if (incident == null) {
            return "Ticket inexistant";
        }

        if (!nomAgent.equals(incident.getAgentResponsable())) {
            return "Vous n'êtes pas l'agent responsable du ticket !";
        }

        if (!"Assigned".equals(incident.getEtat())) {
            return "Le ticket ne peut pas être résolu, si il n'est pas assigné";
        }

        // Résolution du ticket il est assigné et que c'est l'agent responsable qui veut le résoudre
        incident.setEtat("RESOLVED");
        incident.setDateResolution(new java.util.Date());
        incident.setMessageResolution(message);

        sauvegarderDonneesIncident();

        return "Ticket résolu !";
    }

    @Override
    public synchronized String attribuerIncident(Jeton jeton, int id) throws RemoteException {
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

        }
        if (attributionIncident==null){
            return "Id ticket inexistant";
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
            if ("OPEN".equals(incident.getEtat())) {
                ticketsEnAttente.add(incident);
            }
        }
        finLecture();
        return ticketsEnAttente; //liste filtrée

    }

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

    private synchronized void sauvegarderDonneesIncident() {
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

    private boolean estAgentValide(Jeton jeton) throws RemoteException {
        String nomAgent = auth.getLoginParJeton(jeton);
        Role role = auth.getRoleParJeton(jeton);

        return nomAgent != null && role == AGENT;
    }

    public List<Incident> verifRoleAndCreaList(Jeton jeton) throws RemoteException {
        String nomAgent = auth.getLoginParJeton(jeton);
        Role role = auth.getRoleParJeton(jeton);

        if (nomAgent != null && role == AGENT) {
            //liste vide pour les tickets de l'agent
            List<Incident> ticketsAgent = new ArrayList<>();
            return ticketsAgent;
        }

        else {
            return null;
        }
    }

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

    public synchronized void finLecture() {
        nbEnLecture--;
        if(nbEnLecture==0) {
            this.notify();
        }
    }

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

    public synchronized void finEcriture() {
        enEcriture = false;
        this.notifyAll();
    }


    // Partie Stats

    @Override
    public String[] getStatistiques(Jeton jeton) throws RemoteException{
        String nomAgent = auth.getLoginParJeton(jeton);
        Role role = auth.getRoleParJeton(jeton);

        if (nomAgent != null && role == AGENT) {
            String[] listeStat = new String[6];
            listeStat[0] = nbrTotal();
            listeStat[1] = nbrTicketsResolus();
            listeStat[2] = nbrTicketsParEtats();
            listeStat[3] = tempsMoyenResolution();
            listeStat[4] = ticketsParAgents();
            listeStat[5] = tauxPression();
            return listeStat;
        }
        else {
            return null;
        }
    }

    public String nbrTotal (){
        return "Le nombre d'incidents en base est de : " + String.valueOf(incidentEnBase.size());
    }

    public String nbrTicketsResolus () {
        int compte = 0;
        for (int i = 0; i < incidentEnBase.size(); i++) {
            if ("RESOLVED".equals(incidentEnBase.get(i).getEtat())) {
                compte++;
            }
        }
        return "Le nombre de tickets résolus est de " + String.valueOf(compte);
    }

    public String nbrTicketsParEtats(){
        int Open= 0;
        int Assigned = 0 ;
        int Resolved =0;
        for (int i = 0; i < incidentEnBase.size(); i++) {
            Incident current = incidentEnBase.get(i);
            String etatActuel = current.getEtat();
            if ("OPEN".equals(etatActuel)) {
                Open++;
            } else if ("ASSIGNED".equals(etatActuel)) {
                Assigned++;
            } else {
                Resolved++;
            }
        }
        return "Répartition des tickets : " + "Ouverts :" + Open + ", " + "Assignés :" + Assigned +
                "Résolus :" + Resolved + "." ;

    }

    public String tempsMoyenResolution() {
        long totalMinutes = 0;
        int nbTicketsResolus = 0;

        for (int i = 0; i < incidentEnBase.size(); i++) {
            Incident actuel = incidentEnBase.get(i);

            if (actuel.getDateResolution() != null && actuel.getDateCreation() != null) {
                // On calcule la différence entre les deux (en millisecondes à cause de Time)
                long diff = incidentEnBase.get(i).getDateResolution().getTime() - actuel.getDateCreation().getTime();
                // Conversion en minutes (1000ms * 60s)
                long diffMinutes = diff / (1000 * 60);
                totalMinutes += diffMinutes;
                nbTicketsResolus++;
            }
        }

        if (nbTicketsResolus == 0) {
            return "Temps moyen de résolution : N/A (aucun ticket résolu)";
        }

        // Calcul moyenne
        double moyenne = (double) totalMinutes / nbTicketsResolus;

        // Retourne une String propre avec 1 décimale pour les minutes
        return "Temps moyen de résolution : " + String.valueOf(moyenne) + " minutes";
    }






}
