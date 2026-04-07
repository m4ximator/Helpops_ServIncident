package mcpr.helpops_serveurIncident;

import mcpr.hellpops_interfaces.*;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static mcpr.hellpops_interfaces.Role.SUPERVISEUR;

public class GestionnaireSupervision {
    private final LinkedList<String> historiqueEvents = new LinkedList<>();
    private final List<ISupervision> superviseurs = new CopyOnWriteArrayList<>();
    private final IAuthService auth;

    // Le constructeur prend le service d'authentification en paramètre
    public GestionnaireSupervision(IAuthService auth) {
        this.auth = auth;
    }

    // parcours la liste des superviseurs et leurs envoie un message via recevoirEvenement
    public void notifierSuperviseurs(String message) {
        ajoutEventshistorique(message);
        for (ISupervision supervision : superviseurs) {
            try {
                supervision.recevoirEvenement(message);
            } catch (Exception e) {
                System.out.println("Un superviseur s'est déconnecté.");
                superviseurs.remove(supervision);
            }
        }
    }

    // Ajoute un évènement à l'historique et enlève le plus ancien si la liste est pleine
    private synchronized void ajoutEventshistorique(String message) {
        historiqueEvents.add(message);
        if (historiqueEvents.size() > 20) {
            historiqueEvents.removeFirst();
        }
    }

    // vérifie la validité du superviseur
    private boolean estSuperviseurValide(Jeton jeton) throws RemoteException {
        String nomSuperviseur = auth.getLoginParJeton(jeton);
        Role role = auth.getRoleParJeton(jeton);
        return nomSuperviseur != null && role == SUPERVISEUR;
    }

    // Ajoute le superviseur à la liste des superviseur recevant les notifs
    public synchronized void abonnerFluxDirect(ISupervision iSupervision, Jeton jeton) throws RemoteException {
        if (!estSuperviseurValide(jeton)) {
            throw new RemoteException("Accès refusé : Vous devez être un Superviseur.");
        }
        superviseurs.add(iSupervision);
        notifierSuperviseurs("Un nouveau superviseur (" + auth.getLoginParJeton(jeton) + ") a rejoint le flux en direct.");
    }

    // abonne le superviseur et lui renvoie la liste des 20 derniers évènements
    public synchronized List<String> abonnerAvecRattrapage(ISupervision iSupervision, Jeton jeton) throws RemoteException {
        abonnerFluxDirect(iSupervision, jeton);
        return new ArrayList<>(historiqueEvents);
    }
}