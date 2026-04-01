package mcpr.helpops_serveurIncident;

import mcpr.hellpops_interfaces.Incident;
import mcpr.hellpops_interfaces.Jeton;

import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static mcpr.hellpops_interfaces.EtatIncident.RESOLVED;

public class Statistique {

    public Statistique() {
    }

    public String[] getStat(Jeton jeton, List<Incident> incidentEnBase) {
        String[] listeStat = new String[6];
        listeStat[0] = nbrTotal(incidentEnBase);
        listeStat[1] = nbrTicketsResolus(incidentEnBase);
        listeStat[2] = nbrTicketsParEtats(incidentEnBase);
        listeStat[3] = tempsMoyenResolution(incidentEnBase);
        listeStat[4] = ticketsParAgentAff(incidentEnBase);
        listeStat[5] = tauxPression(incidentEnBase);
        return listeStat;
    }

    public String nbrTotal(List<Incident> incidentEnBase){
        return "Le nombre d'incidents en base est de : " + incidentEnBase.size();
    }

    public String nbrTicketsResolus(List<Incident> incidentEnBase) {
        int compte = 0;
        for (Incident current : incidentEnBase) {
            if (current.getEtat() == RESOLVED) {
                compte++;
            }
        }
        return "\nLe nombre de tickets résolus est de " + compte;
    }

    public String nbrTicketsParEtats(List<Incident> incidentEnBase) {
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

    public String tempsMoyenResolution(List<Incident> incidentEnBase) {
        long totalMinutes = 0;
        int nbTicketsResolus = 0;

        for (Incident actuel : incidentEnBase) {
            if (actuel.getDateResolution() != null && actuel.getDateCreation() != null) {
                long diff = actuel.getDateResolution().getTime() - actuel.getDateCreation().getTime();
                long diffMinutes = diff / (1000 * 60);
                totalMinutes += diffMinutes;
                nbTicketsResolus++;
            }
        }

        if (nbTicketsResolus == 0) {
            return "\nTemps moyen de résolution : N/A (aucun ticket résolu)";
        }

        double moyenne = (double) totalMinutes / nbTicketsResolus;
        return "\nTemps moyen de résolution : " + String.format("%.1f", moyenne) + " minutes";
    }

    public Map<String, Integer> ticketsParAgent(List<Incident> incidentEnBase){
        Map<String, Integer> statAgents = new HashMap<>();
        for (Incident incident : incidentEnBase){
            if (incident.getAgentResponsable() != null){
                String agent = incident.getAgentResponsable();
                statAgents.put(agent, statAgents.getOrDefault(agent, 0) + 1);
            }
        }
        return statAgents;
    }

    public String ticketsParAgentAff(List<Incident> incidentEnBase) {
        Map<String, Integer> statAgents = ticketsParAgent(incidentEnBase);

        if (statAgents.isEmpty()) {
            return "\nAucun ticket n'est assigné pour le moment";
        }

        StringBuilder affichage = new StringBuilder("\nRépartition des tickets par agent :\n");

        for (Map.Entry<String, Integer> entree : statAgents.entrySet()) {
            affichage.append("  - Agent '")
                    .append(entree.getKey())
                    .append("' : ")
                    .append(entree.getValue())
                    .append(" ticket(s)\n");
        }
        return affichage.toString().trim();
    }

    public String tauxPression(List<Incident> incidentEnBase) {
        Map<String,Integer> statAgents = ticketsParAgent(incidentEnBase);
        int nbAgents = statAgents.size();
        int nbTotalTickets = incidentEnBase.size();

        if (nbTotalTickets == 0) return "\nTaux de pression : 0 (Aucun ticket)";
        if (nbAgents == 0) return "\nTaux de pression : " + nbTotalTickets + " (Aucun agent n'est assigné)";

        long dateLaPlusAncienne = System.currentTimeMillis();
        for (Incident actuel : incidentEnBase) {
            if (actuel.getDateCreation() != null && actuel.getDateCreation().getTime() < dateLaPlusAncienne) {
                dateLaPlusAncienne = actuel.getDateCreation().getTime();
            }
        }

        long nbJour = (System.currentTimeMillis() - dateLaPlusAncienne) / (1000L * 3600 * 24);
        if (nbJour < 1) nbJour = 1;

        float pression = (float) nbTotalTickets / nbAgents / nbJour;

        StringBuilder affichage = new StringBuilder("\nStatistiques de pression :\n");
        affichage.append(" - Total tickets : ").append(nbTotalTickets).append("\n")
                .append(" - Agents actifs : ").append(nbAgents).append("\n")
                .append(" - Jours d'activité : ").append(nbJour).append("\n")
                .append("   => Taux de pression : ").append(String.format("%.2f", pression))
                .append(" ticket(s) / agent / jour");

        return affichage.toString();
    }
}
