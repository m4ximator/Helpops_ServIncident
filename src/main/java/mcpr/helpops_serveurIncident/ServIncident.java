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
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
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
        return "Ticket résolu !";
    }

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
            return "Ticket assigné avec succes !";
        }
        finEcriture();
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
            if (incident.getEtat() == OPEN) {
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

        if (estAgentValide(jeton)) {
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

        if (estAgentValide(jeton)) {
            debutLecture();

            String[] listeStat = new String[6];
            listeStat[0] = nbrTotal();
            listeStat[1] = nbrTicketsResolus();
            listeStat[2] = nbrTicketsParEtats();
            listeStat[3] = tempsMoyenResolution();
            listeStat[4] = ticketsParAgentAff();
            listeStat[5] = tauxPression();

            finLecture();

            return listeStat;
        }
        else {
            return null;
        }
    }

    public String nbrTotal (){
        return "Le nombre d'incidents en base est de : " + String.valueOf(incidentEnBase.size() + "");
    }

    public String nbrTicketsResolus () {
        int compte = 0;
        for (Incident current : incidentEnBase) {
            if (current.getEtat() == RESOLVED) {
                compte++;
            }
        }
        return "\nLe nombre de tickets résolus est de " + String.valueOf(compte) ;
    }

    public String nbrTicketsParEtats() {
        int open = 0;
        int assigned = 0;
        int resolved = 0;

        for (Incident current : incidentEnBase) {
            switch (current.getEtat()) {
                case OPEN:
                    open++;
                    break;
                case ASSIGNED:
                    assigned++;
                    break;
                case RESOLVED:
                    resolved++;
                    break;
            }
        }
        return String.format("\nRépartition des tickets : Ouverts : %d, Assignés : %d, Résolus : %d",
                open, assigned, resolved);
    }

    public String tempsMoyenResolution() {
        long totalMinutes = 0;
        int nbTicketsResolus = 0;

        for (Incident actuel : incidentEnBase) {

            if (actuel.getDateResolution() != null && actuel.getDateCreation() != null) {
                // On calcule la différence entre les deux (en millisecondes à cause de Time)
                long diff = actuel.getDateResolution().getTime() - actuel.getDateCreation().getTime();
                // Conversion en minutes (1000ms * 60s)
                long diffMinutes = diff / (1000 * 60);
                totalMinutes += diffMinutes;
                nbTicketsResolus++;
            }
        }
        if (nbTicketsResolus == 0) {
            return "\nTemps moyen de résolution : N/A (aucun ticket résolu)";
        }
        // Calcul moyenne
        float moyenne = (float) totalMinutes / nbTicketsResolus;
        // Retourne une String propre avec 1 décimale pour les minutes
        return "\nTemps moyen de résolution : " + String.valueOf(moyenne) + " minutes";
    }

    public Map<String, Integer> ticketsParAgent (){
        Map<String, Integer> statAgents = new HashMap<>();
        for (Incident incident : incidentEnBase){
            if (incident.getAgentResponsable()!=null){
                String agent = incident.getAgentResponsable();
                statAgents.put(agent, statAgents.getOrDefault(agent, 0) + 1);
            }
        }
        return statAgents;
    }

    public String ticketsParAgentAff() {
        Map<String, Integer> statAgents = ticketsParAgent();

        if (statAgents.isEmpty()) {
            return "\nAucun ticket n'est assigné pour le moment";
        }

        StringBuilder affichage = new StringBuilder("\nRépartition des tickets par agent :\n");

        for (Map.Entry<String, Integer> entree : statAgents.entrySet()) {
            String nomAgent = entree.getKey();
            int nbTickets = entree.getValue();

            affichage.append("  - Agent '").append(nomAgent)
                    .append("' : ").append(nbTickets)
                    .append(" ticket(s)");
        }
        return affichage.toString();
    }


    public String tauxPression() {
        Map<String,Integer> statAgents= ticketsParAgent();
        int nbAgents = statAgents.size();
        int nbTotalTickets =incidentEnBase.size();

        // Recherche date + ancienne
        long dateLaPlusAncienne = System.currentTimeMillis();
        for (int i=0;i<incidentEnBase.size();i++) {
            Incident actuel = incidentEnBase.get(i);
            if (actuel.getDateCreation()!=null && actuel.getDateCreation().getTime() < dateLaPlusAncienne) {
                dateLaPlusAncienne=actuel.getDateCreation().getTime();
            }
        }
        if (nbTotalTickets == 0) return "\nTaux de pression : 0 (Aucun ticket)";
        if (nbAgents == 0) return "\nTaux de pression : "+ nbTotalTickets +" (Aucun agent n'est assigné)";

        // Calcul temps et pression
        long nbJour = (System.currentTimeMillis()-dateLaPlusAncienne)/(1000 * 3600 * 24);
            // Cas si ticket crée le jour même que son test
        if (nbJour < 1) {nbJour = 1;}

        double pression = (double)nbTotalTickets/nbAgents/nbJour;

        StringBuilder affichage = new StringBuilder("\nStatistiques de pression :\n");
        affichage.append(" - Total tickets : ").append(nbTotalTickets).append("\n")
                .append(" - Agents actifs : ").append(nbAgents).append("\n")
                .append(" - Jours d'activité : ").append(nbJour).append("\n")
                .append("   => Taux de pression : ").append(String.format("%.2f", pression))
                .append(" ticket(s) / agent / jour");

        return affichage.toString();
    }

}
