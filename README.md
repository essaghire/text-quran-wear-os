# Lecteur texte — Samsung Wear OS

Application native Wear OS pour les montres Samsung récentes (Galaxy Watch 4 et suivantes). Au lancement, elle télécharge le texte arabe du Coran (édition Uthmani). L’écran **Réglages** permet de passer à la traduction anglaise Saheeh International et de régler la taille du texte.

Les commandes du lecteur sont masquées pendant la lecture ; maintenez l’écran appuyé pour les afficher ou les masquer. Balayez vers la gauche pour tourner la page suivante (animation de glissement), ou pincez pour ajuster la taille du texte. Deux inclinaisons brèves du poignet dans la même direction avancent d’une page. Le bouton **Bookmark** se trouve à la fin du texte de chaque page ; les marque-pages sont accessibles depuis **Réglages**. Les réglages permettent de choisir le thème, la couleur du texte et sa taille. Les préférences, marque-pages et textes téléchargés sont conservés dans le stockage privé de l’application ; les textes en cache restent lisibles hors ligne. **Reset defaults** restaure les réglages d’origine sans effacer les textes en cache ni les marque-pages.

## Mise en route

1. Ouvrez ce dossier dans Android Studio (version récente).
2. Connectez une Galaxy Watch en débogage ou démarrez un émulateur Wear OS, puis lancez `app`.

En cas d’échec réseau, le bouton **Réessayer** relance le téléchargement.

## Sources des textes

- Arabe (Uthmani) et anglais (Saheeh International) via [AlQuran Cloud API](https://api.alquran.cloud/).
- Éditions de texte : [`quran-uthmani`](https://api.alquran.cloud/v1/quran/quran-uthmani) et [`en.sahih`](https://api.alquran.cloud/v1/quran/en.sahih).

> Note : les pages sont calculées en fonction de la taille de texte choisie ; changer le zoom peut donc modifier le nombre total de pages.
