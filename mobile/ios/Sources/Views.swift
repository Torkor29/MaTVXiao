import SwiftUI
import UIKit

// The palette of MaTV Connect (the TV's web app), so both feel like one product.
enum Palette {
    static let night = Color(red: 11 / 255, green: 16 / 255, blue: 32 / 255)
    static let nightHi = Color(red: 24 / 255, green: 33 / 255, blue: 61 / 255)
    static let ink = Color(red: 245 / 255, green: 243 / 255, blue: 238 / 255)
    static let dim = ink.opacity(0.6)
    static let faint = ink.opacity(0.32)
    static let glass = Color.white.opacity(0.075)
    static let edge = Color.white.opacity(0.12)
    static let gold = Color(red: 243 / 255, green: 215 / 255, blue: 163 / 255)
    static let onGold = Color(red: 28 / 255, green: 22 / 255, blue: 8 / 255)
    static let ok = Color(red: 123 / 255, green: 224 / 255, blue: 168 / 255)
    static let warn = Color(red: 255 / 255, green: 179 / 255, blue: 138 / 255)
    static let bad = Color(red: 255 / 255, green: 138 / 255, blue: 138 / 255)
}

/// French typography: no line break inside « » or before a colon.
func fr(_ s: String) -> String {
    s.replacingOccurrences(of: "« ", with: "«\u{a0}").replacingOccurrences(of: " »", with: "\u{a0}»")
        .replacingOccurrences(of: " :", with: "\u{a0}:")
}

struct RootView: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        ZStack {
            RadialGradient(colors: [Palette.nightHi, Palette.night], center: UnitPoint(x: 0.85, y: 0.02), startRadius: 0, endRadius: 520)
                .ignoresSafeArea()
            switch m.screen {
            case .list: ListScreen()
            case .guide: GuideScreen()
            case .install: InstallScreen()
            case let .control(name, url): ControlScreen(name: name, url: url)
            }
        }
        .preferredColorScheme(.dark)
        .overlay(alignment: .bottom) {
            if let t = m.toast {
                Text(fr(t)).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                    .padding(.horizontal, 18).padding(.vertical, 12)
                    .background(.ultraThinMaterial, in: Capsule())
                    .padding(.bottom, 90).padding(.horizontal, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .task { try? await Task.sleep(nanoseconds: 3_000_000_000); m.toast = nil }
            }
        }
        .animation(.easeOut(duration: 0.25), value: m.toast)
    }
}

// MARK: - Building blocks

struct Card<Content: View>: View {
    var tint: Color? = nil
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 6) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(tint?.opacity(0.1) ?? Palette.glass))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(tint?.opacity(0.25) ?? Palette.edge, lineWidth: 1))
    }
}

struct Pill: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text).font(.caption.weight(.bold)).foregroundStyle(color).lineLimit(1)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(Capsule().fill(color.opacity(0.12)))
            .overlay(Capsule().stroke(color.opacity(0.25), lineWidth: 1))
    }
}

struct Buttons<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(spacing: 6) { content }
            .padding(.horizontal, 22).padding(.top, 12).padding(.bottom, 8)
            .background(LinearGradient(colors: [Palette.night.opacity(0), Palette.night, Palette.night], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
    }
}

struct PrimaryButton: View {
    let title: String
    var action: (() -> Void)?
    var body: some View {
        Button { action?() } label: {
            Text(title).font(.system(size: 17, weight: .bold)).foregroundStyle(Palette.onGold)
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(Capsule().fill(Palette.gold.opacity(action == nil ? 0.4 : 1)))
        }
        .disabled(action == nil)
        .accessibilityIdentifier(title)
    }
}

struct SecondaryButton: View {
    let title: String
    var action: (() -> Void)?
    var body: some View {
        Button { action?() } label: {
            Text(title).font(.system(size: 15, weight: .bold)).foregroundStyle(action == nil ? Palette.faint : Palette.ink)
                .frame(maxWidth: .infinity, minHeight: 48)
        }
        .disabled(action == nil)
    }
}

struct BackLink: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        Button { m.back() } label: {
            Text("‹  Mes TV").font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.dim).padding(.vertical, 6)
        }
    }
}

struct Title: View {
    let text: String
    var size: CGFloat = 38
    var body: some View {
        Text(fr(text)).font(.system(size: size, weight: .regular, design: .serif)).foregroundStyle(Palette.ink)
            .fixedSize(horizontal: false, vertical: true)
    }
}

struct Txt: View {
    let text: String
    var color: Color = Palette.dim
    var size: CGFloat = 15
    var weight: Font.Weight = .regular
    var body: some View {
        Text(fr(text)).font(.system(size: size, weight: weight)).foregroundStyle(color)
            .lineSpacing(2).fixedSize(horizontal: false, vertical: true)
    }
}

/// A TV: a screen on a thin stand, lit gold when it's ready.
struct TvGlyph: View {
    var lit: Bool
    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 6)
                    .fill(lit ? AnyShapeStyle(LinearGradient(colors: [Color(red: 0.23, green: 0.25, blue: 0.36), Color(red: 0.1, green: 0.13, blue: 0.22)], startPoint: .topLeading, endPoint: .bottomTrailing)) : AnyShapeStyle(Color(red: 0.09, green: 0.11, blue: 0.19)))
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(lit ? Palette.gold.opacity(0.4) : Palette.ink.opacity(0.2), lineWidth: 1.2))
                    .frame(width: w - 2, height: h * 0.8)
                    .offset(x: 1, y: 1)
                if lit {
                    RoundedRectangle(cornerRadius: 3).fill(Palette.gold.opacity(0.43)).frame(width: w * 0.26, height: h * 0.18).offset(x: w * 0.18, y: h * 0.24)
                    RoundedRectangle(cornerRadius: 2).fill(Palette.gold.opacity(0.9)).frame(width: w * 0.44, height: h * 0.08).offset(x: w * 0.18, y: h * 0.52)
                }
                Capsule().fill(Palette.ink.opacity(0.33)).frame(width: w * 0.28, height: 2.4).offset(x: w * 0.36, y: h * 0.86)
            }
        }
    }
}

/// A soft pulsing dot: something is under way.
struct Pulse: View {
    @State private var on = false
    var body: some View {
        ZStack {
            Circle().fill(Palette.gold.opacity(on ? 0 : 0.47)).scaleEffect(on ? 1 : 0.35)
            Circle().fill(Palette.gold).frame(width: 8, height: 8)
        }
        .frame(width: 26, height: 26)
        .onAppear { withAnimation(.linear(duration: 1.4).repeatForever(autoreverses: false)) { on = true } }
    }
}

// MARK: - Screen 1: your TVs

struct ListScreen: View {
    @EnvironmentObject var m: AppModel
    @State private var askIP = false
    @State private var typedIP = ""
    @State private var chosen: SavedTV?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Txt(text: "MaTV", weight: .bold).padding(.bottom, 4)
                Title(text: "Tes TV")
                Txt(text: "Le téléphone et la TV sur le même Wi‑Fi, c'est tout.", size: 16).padding(.top, 8).padding(.bottom, 20)

                if !m.saved.isEmpty {
                    Txt(text: "Pilotées par ce téléphone", weight: .bold).padding(.leading, 4).padding(.bottom, 8)
                    ForEach(m.saved) { s in
                        Button { m.control(s) } label: { savedRow(s) }.buttonStyle(.plain)
                            .contextMenu {
                                Button("Installer ou mettre à jour MaTV") { m.reinstall(s) }
                                Button("Oublier cette TV", role: .destructive) { m.forget(s) }
                            }
                            .padding(.bottom, 10)
                    }
                    Spacer().frame(height: 12)
                }

                if m.localIP == nil {
                    Card {
                        Txt(text: "Pas de Wi‑Fi", color: Palette.ink, size: 19, weight: .bold)
                        Txt(text: "Connecte l'iPhone au Wi‑Fi de la TV pour la trouver.")
                    }
                } else {
                    Txt(text: "Sur le Wi‑Fi", weight: .bold).padding(.leading, 4).padding(.bottom, 8)
                    HStack(spacing: 10) {
                        if m.scanning { Pulse() }
                        Txt(text: statusLine, size: 14)
                    }.padding(.bottom, 12)
                    ForEach(m.freshTVs) { t in
                        Button { m.open(t) } label: { tvRow(t) }.buttonStyle(.plain).padding(.bottom, 10)
                    }
                    if !m.scanning && m.freshTVs.isEmpty && m.saved.isEmpty {
                        Card {
                            Txt(text: "Vérifie que :", color: Palette.ink, size: 16, weight: .bold)
                            Txt(text: "• la TV est allumée (pas en veille) ;\n• l'iPhone est sur le même Wi‑Fi qu'elle ;\n• MaTV a accès au réseau local (Réglages de l'iPhone → MaTV → Réseau local).")
                            Txt(text: "Tu connais l'adresse IP de la TV (Paramètres → Réseau) ? Entre-la ci-dessous.").padding(.top, 6)
                        }
                    }
                }
                Button { m.demo() } label: {
                    Text("Pas de TV sous la main ? Essayer sans TV").font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.gold)
                        .padding(.vertical, 14).padding(.horizontal, 4)
                }.accessibilityIdentifier("demo")
            }
            .padding(.horizontal, 22).padding(.top, 20).padding(.bottom, 24)
        }
        .safeAreaInset(edge: .bottom) {
            Buttons {
                if m.localIP == nil {
                    PrimaryButton(title: "Réessayer") { m.localIP = Discovery.localAddress(); m.scan() }
                } else {
                    PrimaryButton(title: m.found.isEmpty ? "Rechercher les TV" : "Actualiser", action: m.scanning ? nil : { m.scan() })
                    SecondaryButton(title: "Entrer l'adresse IP de la TV") {
                        typedIP = (m.localIP ?? "").replacingOccurrences(of: #"\d+$"#, with: "", options: .regularExpression)
                        askIP = true
                    }
                }
            }
        }
        .alert("Adresse IP de la TV", isPresented: $askIP) {
            TextField("192.168.1.42", text: $typedIP).keyboardType(.decimalPad)
            Button("Continuer") { m.openAddress(typedIP) }
            Button("Annuler", role: .cancel) {}
        } message: {
            Text("Sur la TV : Paramètres → Réseau et Internet → ton Wi‑Fi.")
        }
        .task { if !m.scannedOnce && !m.scanning { m.scan() } }
    }

    private var statusLine: String {
        let net = (m.localIP ?? "").replacingOccurrences(of: #"\.\d+$"#, with: ".x", options: .regularExpression)
        let n = m.freshTVs.count
        if m.scanning { return "Recherche des TV sur le réseau \(net)…" }
        if n == 0 { return m.saved.isEmpty ? "Aucune TV trouvée sur le réseau \(net)." : "Pas d'autre TV sur le réseau \(net)." }
        return (n == 1 ? "1 TV" : "\(n) TV") + " sur le réseau \(net)."
    }

    private func savedRow(_ s: SavedTV) -> some View {
        Card {
            HStack(spacing: 14) {
                TvGlyph(lit: true).frame(width: 54, height: 42)
                VStack(alignment: .leading, spacing: 2) {
                    Txt(text: s.name, color: Palette.ink, size: 18, weight: .bold).lineLimit(1)
                    Txt(text: s.ip, size: 13)
                }
                Spacer(minLength: 8)
                if m.online[s.ip] == false { Pill(text: "Éteinte ?", color: Palette.faint) } else { Pill(text: "Piloter", color: Palette.gold) }
            }
        }
    }

    private func tvRow(_ t: FoundTV) -> some View {
        Card {
            HStack(spacing: 14) {
                TvGlyph(lit: t.ready).frame(width: 54, height: 42)
                VStack(alignment: .leading, spacing: 2) {
                    Txt(text: t.title, color: Palette.ink, size: 18, weight: .bold).lineLimit(1)
                    Txt(text: (t.model.isEmpty || t.model == t.title ? "" : t.model + ", ") + t.ip, size: 13)
                }
                Spacer(minLength: 8)
                Pill(text: t.ready ? "Prête" : "À préparer", color: t.ready ? Palette.ok : Palette.warn)
            }
        }
    }
}

// MARK: - Screen 2: once per TV, turn on its debugging

struct GuideScreen: View {
    @EnvironmentObject var m: AppModel
    private let steps = [
        ("Ouvre les paramètres", "Paramètres → Système → À propos."),
        ("Deviens développeur", "Descends sur « Build du système Android TV » et appuie 7 fois sur OK, jusqu'à « Vous êtes développeur »."),
        ("Ouvre les options pour les développeurs", "Reviens dans Paramètres → Système → Options pour les développeurs."),
        ("Active le débogage USB", "Mets « Débogage USB » sur activé, puis confirme."),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BackLink()
                Title(text: "Prépare « \(m.tv.title) »", size: 30).padding(.top, 14)
                Txt(text: "Une seule fois pour cette TV, avec sa télécommande. C'est ce qui permet à l'iPhone d'y installer MaTV et de la piloter.")
                    .padding(.top, 8).padding(.bottom, 20)
                ForEach(steps.indices, id: \.self) { i in
                    let s = steps[i]
                    HStack(alignment: .top, spacing: 14) {
                        Text("\(i + 1)").font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.onGold)
                            .frame(width: 28, height: 28).background(Circle().fill(Palette.gold))
                        VStack(alignment: .leading, spacing: 3) {
                            Txt(text: s.0, color: Palette.ink, size: 16, weight: .bold)
                            Txt(text: s.1)
                        }.padding(.top, 3)
                    }.padding(.bottom, 16)
                }
                Card {
                    HStack(spacing: 12) {
                        Pulse()
                        Txt(text: m.tvReady ? "La TV est prête." : "L'iPhone attend la TV… il continue tout seul dès que c'est fait.",
                             color: m.tvReady ? Palette.ok : Palette.ink)
                    }
                }.padding(.top, 8)
            }
            .padding(.horizontal, 22).padding(.top, 20).padding(.bottom, 24)
        }
    }
}

// MARK: - Screen 3: install, update, pair

struct InstallScreen: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BackLink()
                HStack(spacing: 14) {
                    TvGlyph(lit: true).frame(width: 62, height: 48)
                    VStack(alignment: .leading, spacing: 2) {
                        Title(text: m.tv.title, size: 28)
                        Txt(text: m.tvInfo.isEmpty ? m.tv.ip : m.tvInfo + ", " + m.tv.ip, size: 13)
                    }
                }.padding(.top, 14).padding(.bottom, 20)
                content
            }
            .padding(.horizontal, 22).padding(.top, 20).padding(.bottom, 24)
        }
        .safeAreaInset(edge: .bottom) { buttons }
    }

    @ViewBuilder private var content: some View {
        switch m.phase {
        case .connecting, .reading:
            Card {
                HStack(spacing: 12) {
                    Pulse()
                    Txt(text: m.phase == .connecting ? "Connexion à la TV…" : "On regarde ce qui est déjà installé…", color: Palette.ink, size: 16)
                }
            }
        case .approve:
            Title(text: "Regarde la TV", size: 24).padding(.bottom, 6)
            Txt(text: "Elle demande si cet iPhone peut s'y connecter. Coche « Toujours autoriser », puis choisis OK.", size: 16).padding(.bottom, 16)
            PromptMock().frame(height: 190)
            HStack(spacing: 10) { Pulse(); Txt(text: "En attente de ta réponse sur la TV…") }.padding(.top, 16)
        case .error:
            Card {
                Txt(text: "Ça n'a pas marché", color: Palette.bad, size: 18, weight: .bold)
                Txt(text: m.error, color: Palette.ink)
                Txt(text: "Vérifie que la TV est allumée et que le débogage USB est toujours activé.", size: 14).padding(.top, 4)
            }
        default:
            if m.phase == .done {
                Card(tint: Palette.ok) {
                    Txt(text: "C'est installé", color: Palette.ok, size: 19, weight: .bold)
                    Txt(text: "Sur la TV, choisis MaTV comme écran d'accueil (« Ouvrir MaTV sur la TV » y mène). La TV demande encore « Autoriser le débogage » : cette fois c'est MaTV, accepte en cochant « Toujours autoriser ».", color: Palette.ink)
                    Txt(text: "Ensuite, la TV se met à jour toute seule, et cet iPhone la pilote.", size: 14).padding(.top, 4)
                }.padding(.bottom, 16)
            }
            Txt(text: m.phase == .done ? "Sur cette TV" : "MaTV sur cette TV", weight: .bold).padding(.leading, 4).padding(.bottom, 8)
            ForEach(m.items) { it in
                Button { m.toggle(it) } label: { row(it) }.buttonStyle(.plain).padding(.bottom, 10)
            }
            if m.playProtectHint {
                Txt(text: "Si la TV affiche Play Protect : « Plus d'infos » → « Installer quand même ». MaTV n'est pas sur le Play Store de la TV, c'est normal.", color: Palette.warn, size: 14)
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 16).fill(Palette.warn.opacity(0.08)))
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(Palette.warn.opacity(0.2), lineWidth: 1))
            }
        }
    }

    private func row(_ it: AppItem) -> some View {
        Card {
            HStack(spacing: 14) {
                ZStack {
                    if it.done || (it.wanted && !it.url.isEmpty) {
                        Circle().fill(it.done ? Palette.ok : Palette.gold)
                        Image(systemName: "checkmark").font(.system(size: 12, weight: .heavy)).foregroundStyle(Palette.onGold)
                    } else {
                        Circle().stroke(Palette.faint, lineWidth: 1.5)
                    }
                }.frame(width: 26, height: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Txt(text: it.title, color: Palette.ink, size: 17, weight: .bold)
                    Txt(text: it.about, size: 13)
                }
                Spacer(minLength: 8)
                state(it)
            }
            if !it.status.isEmpty {
                if !it.done && !it.failed {
                    ProgressView(value: it.fraction).tint(Palette.gold).padding(.leading, 40).padding(.top, 8)
                }
                Txt(text: it.status, color: it.failed ? Palette.bad : it.done ? Palette.ok : Palette.dim, size: 13).padding(.leading, 40)
            }
        }
    }

    @ViewBuilder private func state(_ it: AppItem) -> some View {
        if it.url.isEmpty { Pill(text: "Indisponible", color: Palette.faint) }
        else if it.installed.isEmpty { Pill(text: it.version.isEmpty ? "Nouveau" : it.version, color: Palette.gold) }
        else if it.upToDate { Pill(text: "À jour", color: Palette.ok) }
        else { Pill(text: "\(it.installed) → \(it.version)", color: Palette.gold) }
    }

    @ViewBuilder private var buttons: some View {
        let count = m.items.filter { $0.wanted && !$0.url.isEmpty }.count
        let homeReady = m.items.first?.upToDate ?? false
        Buttons {
            switch m.phase {
            case .error:
                PrimaryButton(title: "Réessayer") { m.startInstall() }
                SecondaryButton(title: "Choisir une autre TV") { m.back() }
            case .installing:
                PrimaryButton(title: "Installation…")
            case .done, .choose:
                if m.pairing {
                    PrimaryButton(title: "Connexion…")
                } else if m.phase == .done || (count == 0 && homeReady) {
                    PrimaryButton(title: "Piloter la TV") { m.pairAndControl() }
                    SecondaryButton(title: "Ouvrir MaTV sur la TV") { m.openOnTV() }
                } else if count == 0 {
                    PrimaryButton(title: "Choisis ce qu'il faut installer")
                } else {
                    let update = m.items.contains { $0.wanted && !$0.installed.isEmpty }
                    PrimaryButton(title: update ? "Mettre à jour (\(count))" : count == 1 ? "Installer 1 appli" : "Installer \(count) applis") { m.runInstall() }
                    if homeReady { SecondaryButton(title: "Piloter la TV sans mettre à jour") { m.pairAndControl() } }
                }
            default:
                EmptyView()
            }
        }
    }
}

/// What the TV shows: "Allow USB debugging?" with "Always allow" ticked and OK focused.
struct PromptMock: View {
    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                RoundedRectangle(cornerRadius: 14).fill(Color(red: 0.02, green: 0.035, blue: 0.07))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Palette.edge, lineWidth: 1))
                VStack(alignment: .leading, spacing: 6) {
                    Text("Autoriser le débogage USB ?").font(.system(size: 14, weight: .bold)).foregroundStyle(Palette.ink)
                    Text("Empreinte de la clé RSA : 3F:A2:…").font(.system(size: 11)).foregroundStyle(Palette.dim)
                    HStack(spacing: 8) {
                        Image(systemName: "checkmark.square.fill").foregroundStyle(Palette.gold)
                        Text("Toujours autoriser").font(.system(size: 12)).foregroundStyle(Palette.ink)
                    }.padding(.top, 6)
                    HStack {
                        Spacer()
                        Text("ANNULER").font(.system(size: 12)).foregroundStyle(Palette.dim).padding(.trailing, 14)
                        Text("OK").font(.system(size: 12, weight: .bold)).foregroundStyle(Palette.onGold)
                            .padding(.horizontal, 18).padding(.vertical, 6).background(Capsule().fill(Palette.gold))
                    }.padding(.top, 6)
                }
                .padding(16)
                .frame(maxWidth: 320)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(red: 0.165, green: 0.184, blue: 0.227)))
                .padding(.horizontal, 24).padding(.vertical, 16)
            }
            Capsule().fill(Palette.ink.opacity(0.33)).frame(width: 70, height: 3).padding(.top, 5)
        }
    }
}
