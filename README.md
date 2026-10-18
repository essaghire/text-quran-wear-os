# Text Quran — Wear OS

Application native Wear OS pour les montres Samsung récentes (Galaxy Watch 4 et suivantes). Au lancement, elle télécharge le texte arabe du Coran (édition Uthmani). L’écran **Réglages**, accessible par la petite roue dentée à la fin de chaque page, permet de passer à la traduction anglaise Saheeh International, de régler la taille du texte, la vitesse de défilement automatique et le symbole de fin d’ayah. L’application s’installe directement sur la montre et ne nécessite pas d’application compagnon sur téléphone. Le thème sombre est sélectionné par défaut.

En haut de chaque page, les boutons compacts permettent d’aller à la fin de la page ou de démarrer/arrêter le défilement automatique. Balayez vers la gauche pour tourner la page suivante (animation de glissement), ou pincez pour ajuster la taille du texte. Deux inclinaisons brèves du poignet dans la même direction avancent d’une page. Les boutons signet et réglages se trouvent à la fin de la page ; les marque-pages sont accessibles depuis **Réglages**. Les réglages permettent de choisir le thème, la couleur, la famille de police, le gras et le symbole de fin d’ayah (۝, puce •, cercle ◦, étoile ✦ ou aucun). Le numéro associé au symbole est lu depuis `numberInSurah` pour chaque ayah renvoyé par l’API, et la pagination conserve les ayahs entiers afin que le symbole reste en fin de verset. Les préférences, marque-pages et textes téléchargés sont conservés dans le stockage privé de l’application ; les textes en cache restent lisibles hors ligne. **Reset defaults** restaure les réglages d’origine sans effacer les textes en cache ni les marque-pages.

## Mise en route

1. Ouvrez ce dossier dans Android Studio (version récente).
2. Connectez une Galaxy Watch en débogage ou démarrez un émulateur Wear OS, puis lancez `app`.

En cas d’échec réseau, le bouton **Réessayer** relance le téléchargement.

## Préparation Google Play

- Nom d’application : **Text Quran**.
- L’application est déclarée autonome pour Wear OS dans le manifeste.
- Identifiant de paquet permanent : `com.essaghir.textquran` (`applicationId` et namespace Android).
- Version initiale de publication : `0.1` (`versionCode = 1`).
- `targetSdk = 35` respecte l’exigence Google Play pour les nouvelles applications Wear OS à compter du 31 août 2026 ([exigences API](https://developer.android.com/google/play/requirements/target-sdk)).
- L’Android App Bundle de publication est configuré pour être signé avec une clé d’envoi fournie uniquement par variables d’environnement. Définissez `TEXTQURAN_UPLOAD_STORE_FILE`, `TEXTQURAN_UPLOAD_STORE_PASSWORD`, `TEXTQURAN_UPLOAD_KEY_ALIAS` et `TEXTQURAN_UPLOAD_KEY_PASSWORD`, puis exécutez `:app:bundleRelease`. Ne stockez pas les mots de passe dans Git ; activez Play App Signing dans Play Console.
- Les identifiants de signature ne sont pas configurés dans ce dépôt. La génération de `bundleRelease` s’arrête explicitement tant que les quatre variables de signature ne sont pas définies.
- Les éléments graphiques de fiche sont préparés dans [`play-assets/`](./play-assets/) : icône 512×512 et graphique promotionnel 1024×500. La capture d’écran réelle de la montre reste à ajouter.
- [`PLAY_STORE_LISTING.md`](./PLAY_STORE_LISTING.md) contient un brouillon de fiche. [`PRIVACY_POLICY.md`](./PRIVACY_POLICY.md) est un brouillon à compléter avec un contact valide et à héberger sur une URL publique ; il ne doit pas être publié tel quel.
- Avant la soumission, complétez les déclarations Data safety et App content dans Play Console, ajoutez la capture Wear OS, vérifiez l’attribution et les conditions de redistribution des éditions, et configurez Play App Signing avec votre clé d’envoi.

## Sources des textes

- Arabe (Uthmani) et anglais (Saheeh International) via [AlQuran Cloud API](https://api.alquran.cloud/).
- Éditions de texte : [`quran-uthmani`](https://api.alquran.cloud/v1/quran/quran-uthmani) et [`en.sahih`](https://api.alquran.cloud/v1/quran/en.sahih).

> Note : les pages sont calculées en fonction de la taille de texte choisie ; changer le zoom peut donc modifier le nombre total de pages.
