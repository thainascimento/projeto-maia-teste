import { fetchAutenticado } from '@/services/api';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { ComponentProps } from 'react';
import { buscarUsuario } from '@/services/auth';
import { router, Stack, useLocalSearchParams } from 'expo-router';
import * as Location from 'expo-location';
import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Animated,
  Dimensions,
  PanResponder,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

type Atracao = {
  id: string | number;
  nome: string;
  categoria?: string | null;
  endereco?: string | null;
  bairro?: string | null;
  cidade?: string | null;
  distanciaMetros?: number | null;
  latitude?: number;
  longitude?: number;
};

type Coordenadas = { latitude: number; longitude: number };
type ModoExploracao = 'viagem' | 'gps';
type Viagem = {
  id: number;
  destino: string;
  cidade: string | null;
  estado: string | null;
  pais: string | null;
  latitude: number | null;
  longitude: number | null;
  status: 'PLANEJADA' | 'EM_ANDAMENTO' | 'FINALIZADA' | 'CANCELADA';
};

const LARGURA_TELA = Dimensions.get('window').width;
const LIMIAR_DESLIZE = 90;

function coordenadasValidas(latitude: unknown, longitude: unknown): latitude is number {
  return typeof latitude === 'number' && typeof longitude === 'number' &&
    Number.isFinite(latitude) && Number.isFinite(longitude) &&
    Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180 &&
    !(latitude === 0 && longitude === 0);
}

function nomeDaViagem(viagem: Viagem): string {
  return [viagem.cidade, viagem.estado, viagem.pais].filter(Boolean).join(', ') ||
    viagem.destino || 'Destino da viagem';
}

function numeroParametro(valor: string | string[] | undefined): number | null {
  const primeiro = Array.isArray(valor) ? valor[0] : valor;
  if (!primeiro?.trim()) return null;
  const numero = Number(primeiro);
  return Number.isFinite(numero) ? numero : null;
}

function formatarDistancia(valor?: number | null): string {
  if (valor == null || !Number.isFinite(valor)) return 'Distância indisponível';
  if (valor < 1000) return `${Math.round(valor)} m de distância`;
  return `${(valor / 1000).toFixed(1).replace('.', ',')} km de distância`;
}

type NomeIcone = ComponentProps<typeof MaterialCommunityIcons>['name'];

function iconeDaAtracao(atracao: Atracao): NomeIcone {
  const texto = `${atracao.categoria || ''} ${atracao.nome || ''}`.toLowerCase();
  if (/igreja|catedral|capela|templo|santu[aá]rio/.test(texto)) return 'church';
  if (/praia|mar|piscina natural|lago|lagoa/.test(texto)) return 'beach';
  if (/museu|hist[oó]ria|cultura|teatro|galeria|centro cultural/.test(texto)) return 'bank';
  if (/parque|jardim|natureza|trilha|cachoeira|reserva/.test(texto)) return 'tree';
  if (/restaurante|comida|gastronomia|mercado|feira/.test(texto)) return 'silverware-fork-knife';
  if (/mirante|vista|farol|montanha|serra/.test(texto)) return 'image-filter-hdr';
  if (/est[aá]tua|monumento|pra[cç]a|escultura/.test(texto)) return 'map-marker';
  return 'star-four-points';
}

export default function ExploraScreen() {
  const parametros = useLocalSearchParams<{ lat?: string; lon?: string; destino?: string }>();
  const [atracoes, setAtracoes] = useState<Atracao[]>([]);
  const [indice, setIndice] = useState(0);
  const [favoritos, setFavoritos] = useState<Atracao[]>([]);
  const [carregando, setCarregando] = useState(false);
  const [erro, setErro] = useState('');
  const [viagemEmAndamento, setViagemEmAndamento] = useState<Viagem | null>(null);
  const [destinoAtual, setDestinoAtual] = useState('');
  const [modoExploracao, setModoExploracao] = useState<ModoExploracao>('gps');
  const [coordenadasAtuais, setCoordenadasAtuais] = useState<Coordenadas | null>(null);
  const requisicaoAtual = useRef(0);
  const posicao = useRef(new Animated.ValueXY()).current;
  const animando = useRef(false);
  const atracaoAtual = atracoes[indice];

  async function carregarAtracoes(coordenadas: Coordenadas, rotulo: string) {
    const requisicao = ++requisicaoAtual.current;
    setCarregando(true);
    setErro('');
    setDestinoAtual(rotulo);
    setCoordenadasAtuais(coordenadas);
    setAtracoes([]);
    setIndice(0);
    setFavoritos([]);
    posicao.setValue({ x: 0, y: 0 });
    animando.current = false;
    try {
      const busca = `lat=${encodeURIComponent(String(coordenadas.latitude))}&lon=${encodeURIComponent(String(coordenadas.longitude))}`;
      const resposta = await fetchAutenticado(`/maia-explora/atracoes?${busca}`);
      if (!resposta.ok) throw new Error(`HTTP ${resposta.status}`);
      const dados: unknown = await resposta.json();
      if (!Array.isArray(dados)) throw new Error('Resposta inesperada do backend');
      if (requisicao !== requisicaoAtual.current) return;
      setAtracoes(dados as Atracao[]);
    } catch (e) {
      if (requisicao !== requisicaoAtual.current) return;
      console.warn('Erro ao carregar atrações:', e);
      setErro('Não foi possível carregar as atrações. Verifique sua conexão e tente novamente.');
    } finally {
      if (requisicao === requisicaoAtual.current) setCarregando(false);
    }
  }

  async function explorarViagem(viagem: Viagem) {
    setModoExploracao('viagem');
    if (coordenadasValidas(viagem.latitude, viagem.longitude)) {
      await carregarAtracoes(
        { latitude: viagem.latitude!, longitude: viagem.longitude! },
        nomeDaViagem(viagem)
      );
      return;
    }
    const texto = [viagem.cidade, viagem.estado, viagem.pais].filter(Boolean).join(', ') || viagem.destino;
    if (!texto.trim()) {
      setErro('A viagem em andamento não possui um destino válido.');
      setCarregando(false);
      return;
    }
    const requisicao = ++requisicaoAtual.current;
    setCarregando(true);
    setErro('');
    try {
      const resposta = await fetchAutenticado(`/geocoding/buscar?texto=${encodeURIComponent(texto)}`);
      if (!resposta.ok) throw new Error(`HTTP ${resposta.status}`);
      const dados: { nome: string; latitude: number; longitude: number } = await resposta.json();
      if (!coordenadasValidas(dados.latitude, dados.longitude)) throw new Error('Coordenadas inválidas');
      if (requisicao !== requisicaoAtual.current) return;
      await carregarAtracoes({ latitude: dados.latitude, longitude: dados.longitude }, dados.nome || nomeDaViagem(viagem));
    } catch (e) {
      if (requisicao !== requisicaoAtual.current) return;
      console.warn('Erro ao localizar destino da viagem:', e);
      setErro('Não foi possível localizar o destino da viagem.');
      setCarregando(false);
    }
  }

  async function explorarPertoDeMim() {
    setModoExploracao('gps');
    const requisicao = ++requisicaoAtual.current;
    setCarregando(true);
    setErro('');
    try {
      const permissao = await Location.requestForegroundPermissionsAsync();
      if (permissao.status !== 'granted') throw new Error('PERMISSAO_NEGADA');
      const pos = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
      if (requisicao !== requisicaoAtual.current) return;
      await carregarAtracoes(
        { latitude: pos.coords.latitude, longitude: pos.coords.longitude },
        'Perto de mim'
      );
    } catch (e) {
      if (requisicao !== requisicaoAtual.current) return;
      console.warn('Erro ao obter GPS:', e);
      setErro(e instanceof Error && e.message === 'PERMISSAO_NEGADA'
        ? 'Autorize a localização nas configurações do dispositivo para explorar perto de você.'
        : 'Não foi possível obter sua localização. Verifique o GPS e tente novamente.');
      setCarregando(false);
    }
  }

  useEffect(() => {
    let cancelado = false;
    async function iniciarExplora() {
      const lat = numeroParametro(parametros.lat);
      const lon = numeroParametro(parametros.lon);
      if (lat !== null && lon !== null && coordenadasValidas(lat, lon)) {
        setModoExploracao('viagem');
        await carregarAtracoes(
          { latitude: lat, longitude: lon },
          parametros.destino || 'Destino selecionado'
        );
        return;
      }
      setCarregando(true);
      setErro('');
      try {
        const usuario = await buscarUsuario();
        if (!usuario?.id) throw new Error('USUARIO_NAO_IDENTIFICADO');
        const resposta = await fetchAutenticado(`/viagens?usuarioId=${usuario.id}`);
        if (!resposta.ok) throw new Error(`HTTP ${resposta.status}`);
        const viagens: Viagem[] = await resposta.json();
        if (!Array.isArray(viagens)) throw new Error('Resposta inválida de viagens');
        if (cancelado) return;
        const ativa = viagens.find((viagem) => viagem.status === 'EM_ANDAMENTO') ?? null;
        setViagemEmAndamento(ativa);
        if (ativa) await explorarViagem(ativa);
        else await explorarPertoDeMim();
      } catch (e) {
        if (cancelado) return;
        console.warn('Erro ao verificar viagem em andamento:', e);
        setErro('Não foi possível consultar suas viagens. Tente novamente.');
        setCarregando(false);
      }
    }
    void iniciarExplora();
    return () => { cancelado = true; requisicaoAtual.current++; };
  }, [parametros.lat, parametros.lon, parametros.destino]);

  function escolher(gostou: boolean) {
    if (!atracaoAtual) return;
    if (gostou) {
      setFavoritos((lista) =>
        lista.some((item) => String(item.id) === String(atracaoAtual.id))
          ? lista : [...lista, atracaoAtual]
      );
    }
    setIndice((anterior) => anterior + 1);
  }

  // Referências mantêm os gestos ligados ao cartão atual, sem perder o estado.
  const escolherAtual = useRef<(gostou: boolean) => void>(() => {});
  escolherAtual.current = escolher;
  const podeDeslizar = useRef(false);
  podeDeslizar.current = !!atracaoAtual && !carregando && !erro;

  function animarEscolha(gostou: boolean) {
    if (!podeDeslizar.current || animando.current) return;
    animando.current = true;
    Animated.timing(posicao, {
      toValue: { x: (gostou ? 1 : -1) * LARGURA_TELA * 1.25, y: 0 },
      duration: 220,
      useNativeDriver: true,
    }).start(({ finished }) => {
      posicao.setValue({ x: 0, y: 0 });
      animando.current = false;
      if (finished) escolherAtual.current(gostou);
    });
  }

  const animarEscolhaAtual = useRef<(gostou: boolean) => void>(() => {});
  animarEscolhaAtual.current = animarEscolha;

  const panResponder = useRef(PanResponder.create({
    // O cartão recebe o toque desde o início para o ScrollView não assumir o gesto.
    onStartShouldSetPanResponder: () => podeDeslizar.current && !animando.current,
    onStartShouldSetPanResponderCapture: () => podeDeslizar.current && !animando.current,
    onPanResponderTerminationRequest: () => false,
    onMoveShouldSetPanResponderCapture: (_evento, gesto) =>
      podeDeslizar.current && !animando.current &&
      Math.abs(gesto.dx) > 10 && Math.abs(gesto.dx) > Math.abs(gesto.dy) * 1.2,
    onMoveShouldSetPanResponder: (_evento, gesto) =>
      podeDeslizar.current && !animando.current &&
      Math.abs(gesto.dx) > 12 && Math.abs(gesto.dx) > Math.abs(gesto.dy) * 1.2,
    onPanResponderMove: (_evento, gesto) => {
      if (!animando.current) posicao.setValue({ x: gesto.dx, y: 0 });
    },
    onPanResponderRelease: (_evento, gesto) => {
      if (gesto.dx > LIMIAR_DESLIZE) animarEscolhaAtual.current(true);
      else if (gesto.dx < -LIMIAR_DESLIZE) animarEscolhaAtual.current(false);
      else Animated.spring(posicao, {
        toValue: { x: 0, y: 0 },
        useNativeDriver: true,
        bounciness: 6,
      }).start();
    },
    onPanResponderTerminate: () => {
      Animated.spring(posicao, {
        toValue: { x: 0, y: 0 },
        useNativeDriver: true,
      }).start();
    },
  })).current;

  const rotacao = posicao.x.interpolate({
    inputRange: [-LARGURA_TELA, 0, LARGURA_TELA],
    outputRange: ['-18deg', '0deg', '18deg'],
    extrapolate: 'clamp',
  });

  return (
    <>
      <Stack.Screen options={{ gestureEnabled: false }} />
      <ScrollView style={styles.tela} contentContainerStyle={styles.conteudo}>
      <Pressable onPress={() => router.back()} style={styles.voltar}>
        <Text style={styles.voltarTexto}>‹ VOLTAR</Text>
      </Pressable>
      <Text style={styles.etiqueta}>MAIA EXPLORA</Text>
      <Text style={styles.titulo}>Lugares que combinam com você</Text>
      <Text style={styles.subtitulo}>
        Deslize para a direita se quiser conhecer ou para a esquerda para passar.
      </Text>
      {!!destinoAtual && (
        <View style={styles.localizacao}>
          <View style={styles.linhaComIcone}>
            <MaterialCommunityIcons name="map-marker" size={17} color="#6331b2" />
            <Text style={styles.localizacaoTexto}>Explorando {destinoAtual}</Text>
          </View>
        </View>
      )}

      <View style={styles.modosContainer}>
        <Text style={styles.modosTitulo}>ONDE VOCÊ QUER EXPLORAR?</Text>
        <View style={styles.modosLinha}>
          {viagemEmAndamento && (
            <Pressable
              style={[styles.botaoModo, modoExploracao === 'viagem' && styles.botaoModoAtivo]}
              onPress={() => { if (!carregando) void explorarViagem(viagemEmAndamento); }}
              disabled={carregando}
              accessibilityRole="button"
              accessibilityLabel="Explorar o destino da viagem"
              accessibilityState={{ selected: modoExploracao === 'viagem', disabled: carregando }}
            >
              <MaterialCommunityIcons name="airplane" size={18} color={modoExploracao === 'viagem' ? '#fff' : '#6d28d9'} />
              <Text style={[styles.botaoModoTexto, modoExploracao === 'viagem' && styles.botaoModoTextoAtivo]}>MINHA VIAGEM</Text>
            </Pressable>
          )}
          <Pressable
            style={[styles.botaoModo, modoExploracao === 'gps' && styles.botaoModoAtivo]}
            onPress={() => { if (!carregando) void explorarPertoDeMim(); }}
            disabled={carregando}
            accessibilityRole="button"
            accessibilityLabel="Explorar perto da minha localização atual"
            accessibilityState={{ selected: modoExploracao === 'gps', disabled: carregando }}
          >
            <MaterialCommunityIcons name="crosshairs-gps" size={18} color={modoExploracao === 'gps' ? '#fff' : '#6d28d9'} />
            <Text style={[styles.botaoModoTexto, modoExploracao === 'gps' && styles.botaoModoTextoAtivo]}>PERTO DE MIM</Text>
          </Pressable>
        </View>
      </View>

      {carregando ? (
        <View style={styles.estado}>
          <ActivityIndicator color="#6d28d9" size="large" />
          <Text style={styles.estadoTexto}>Buscando atrações...</Text>
        </View>
      ) : erro ? (
        <View style={styles.estado}>
          <Text style={styles.estadoTexto}>{erro}</Text>
          <Pressable style={styles.botaoRecarregar} onPress={() => {
            if (modoExploracao === 'gps') void explorarPertoDeMim();
            else if (viagemEmAndamento) void explorarViagem(viagemEmAndamento);
            else if (coordenadasAtuais) void carregarAtracoes(coordenadasAtuais, destinoAtual);
            else void explorarPertoDeMim();
          }}>
            <Text style={styles.botaoRecarregarTexto}>TENTAR NOVAMENTE</Text>
          </Pressable>
        </View>
      ) : !coordenadasAtuais ? (
        <View style={styles.estado}>
          <Text style={styles.estadoTexto}>Estamos identificando seu destino para encontrar atrações.</Text>
        </View>
      ) : atracoes.length === 0 ? (
        <View style={styles.estado}>
          <Text style={styles.estadoTexto}>Nenhuma atração encontrada para esta localização.</Text>
        </View>
      ) : atracaoAtual ? (
        <>
          <View style={styles.progressoLinha}>
            <Text style={styles.contador}>LUGAR {indice + 1} DE {atracoes.length}</Text>
            <View style={styles.linhaComIcone}>
              <MaterialCommunityIcons name="heart" size={15} color="#6d28d9" />
              <Text style={styles.contadorSecundario}>{favoritos.length} favoritos</Text>
            </View>
          </View>
          <Animated.View
            {...panResponder.panHandlers}
            style={[styles.cartao, { transform: [{ translateX: posicao.x }, { rotate: rotacao }] }]}
          >
            <View style={styles.cartaoTopo}>
              <View style={styles.iconeCirculo}>
                <MaterialCommunityIcons name={iconeDaAtracao(atracaoAtual)} size={51} color="#6d28d9" />
              </View>
              <Text style={styles.cartaoEtiqueta}>DESCUBRA ESTE LUGAR</Text>
              <Text style={styles.nome}>{atracaoAtual.nome || 'Atração sem nome'}</Text>
              {!!atracaoAtual.categoria && (
                <Text style={styles.categoria}>{atracaoAtual.categoria}</Text>
              )}
            </View>
            <View style={styles.cartaoDetalhes}>
              <View style={styles.linhaComIcone}>
                <MaterialCommunityIcons name="map-marker-outline" size={18} color="#6d28d9" />
                <Text style={[styles.bairro, styles.textoFlex]}>{atracaoAtual.bairro || atracaoAtual.cidade || destinoAtual || 'Local não informado'}</Text>
              </View>
              <View style={styles.linhaComIcone}>
                <MaterialCommunityIcons name="map-marker-distance" size={18} color="#342744" />
                <Text style={styles.distancia}>{formatarDistancia(atracaoAtual.distanciaMetros)}</Text>
              </View>
              {!!atracaoAtual.endereco && (
                <Text style={styles.endereco}>{atracaoAtual.endereco}</Text>
              )}
              <View style={styles.dicaContainer}>
                <View style={styles.dicaLado}>
                  <MaterialCommunityIcons name="arrow-left" size={16} color="#9887ae" />
                  <Text style={styles.dica}>DESLIZE PRA CÁ</Text>
                  <MaterialCommunityIcons name="close" size={16} color="#9887ae" />
                </View>
                <View style={styles.dicaLado}>
                  <Text style={styles.dica}>OU PRA LÁ</Text>
                  <MaterialCommunityIcons name="heart" size={16} color="#6d28d9" />
                  <MaterialCommunityIcons name="arrow-right" size={16} color="#9887ae" />
                </View>
              </View>
            </View>
          </Animated.View>
          <View style={styles.acoes}>
            <Pressable
              style={[styles.acao, styles.descartar]}
              onPress={() => animarEscolha(false)}
              accessibilityRole="button"
              accessibilityLabel="Passar esta atração"
            >
              <View style={styles.botaoConteudo}>
                <MaterialCommunityIcons name="close" size={19} color="#7e5c99" />
                <Text style={styles.descartarTexto}>PASSAR</Text>
              </View>
            </Pressable>
            <Pressable
              style={[styles.acao, styles.gostei]}
              onPress={() => animarEscolha(true)}
              accessibilityRole="button"
              accessibilityLabel="Quero visitar esta atração"
            >
              <View style={styles.botaoConteudo}>
                <MaterialCommunityIcons name="heart" size={17} color="#fff" />
                <Text style={styles.gosteiTexto}>QUERO VISITAR</Text>
              </View>
            </Pressable>
          </View>
        </>
      ) : (
        <View style={styles.estado}>
          <Text style={styles.finalTitulo}>Você explorou todos os lugares!</Text>
          <Text style={styles.estadoTexto}>Você selecionou {favoritos.length} {favoritos.length === 1 ? 'lugar' : 'lugares'}.</Text>
          <Pressable style={styles.botaoRecarregar} onPress={() => {
            if (coordenadasAtuais) void carregarAtracoes(coordenadasAtuais, destinoAtual);
          }}>
            <Text style={styles.botaoRecarregarTexto}>EXPLORAR NOVAMENTE</Text>
          </Pressable>
        </View>
      )}

      {favoritos.length > 0 && (
        <View style={styles.favoritos}>
          <View style={styles.tituloFavoritosLinha}>
            <MaterialCommunityIcons name="heart" size={22} color="#6d28d9" />
            <Text style={styles.favoritosTitulo}>Lugares que quero visitar ({favoritos.length})</Text>
          </View>
          {favoritos.map((item) => (
            <View key={String(item.id)} style={styles.favoritoItem}>
              <Text style={styles.favoritoNome}>{item.nome}</Text>
              <Text style={styles.favoritoBairro}>{item.bairro || item.cidade || destinoAtual || 'Local não informado'}</Text>
            </View>
          ))}
          <Text style={styles.aviso}>Por enquanto, as escolhas são temporárias e ainda não são salvas no MySQL nem geram um roteiro automaticamente.</Text>
        </View>
      )}
      </ScrollView>
    </>
  );
}

const styles = StyleSheet.create({
  tela: { flex: 1, backgroundColor: '#f7f7fb' },
  conteudo: { paddingTop: 56, paddingHorizontal: 20, paddingBottom: 80 },
  voltar: { alignSelf: 'flex-start', marginBottom: 22 },
  voltarTexto: { color: '#6d28d9', fontSize: 13, fontWeight: '800' },
  etiqueta: { color: '#6d28d9', fontSize: 12, fontWeight: '900', letterSpacing: 1.5, marginBottom: 8 },
  titulo: { color: '#241b35', fontSize: 28, fontWeight: '900', marginBottom: 10 },
  subtitulo: { color: '#777080', fontSize: 14, lineHeight: 21, marginBottom: 18 },
  localizacao: { backgroundColor: '#ede5ff', borderRadius: 18, paddingHorizontal: 14, paddingVertical: 12, marginBottom: 20 },
  localizacaoTexto: { color: '#6331b2', fontSize: 13, fontWeight: '700' },
  modosContainer: { marginBottom: 20 },
  modosTitulo: { fontSize: 10, fontWeight: '900', color: '#7e6a95', letterSpacing: 1, marginBottom: 10 },
  modosLinha: { flexDirection: 'row', gap: 10 },
  botaoModo: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 7, paddingVertical: 14, paddingHorizontal: 10, backgroundColor: '#fff', borderWidth: 1, borderColor: '#d9cce9', borderRadius: 15 },
  botaoModoAtivo: { backgroundColor: '#6d28d9', borderColor: '#6d28d9' },
  botaoModoTexto: { color: '#6d28d9', fontSize: 11, fontWeight: '900' },
  botaoModoTextoAtivo: { color: '#fff' },
  estado: { backgroundColor: '#fff', padding: 28, borderRadius: 22, alignItems: 'center', marginTop: 15 },
  estadoTexto: { color: '#777080', fontSize: 14, lineHeight: 21, textAlign: 'center', marginTop: 12 },
  progressoLinha: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 },
  contador: { color: '#6d28d9', fontSize: 12, fontWeight: '800' },
  contadorSecundario: { color: '#777080', fontSize: 12, fontWeight: '700' },
  cartao: { backgroundColor: '#fff', borderRadius: 25, overflow: 'hidden', borderWidth: 1, borderColor: '#e8def6', elevation: 3 },
  cartaoTopo: { minHeight: 280, backgroundColor: '#6d28d9', paddingHorizontal: 25, paddingVertical: 27, alignItems: 'center', justifyContent: 'center' },
  iconeCirculo: { width: 86, height: 86, borderRadius: 43, backgroundColor: '#fff', alignItems: 'center', justifyContent: 'center', marginBottom: 18 },
  cartaoEtiqueta: { color: '#e9d5ff', fontSize: 11, fontWeight: '800', letterSpacing: 1.5, marginBottom: 12 },
  nome: { color: '#fff', fontSize: 25, fontWeight: '900', textAlign: 'center', marginBottom: 10 },
  categoria: { color: '#f3e8ff', fontSize: 13, fontWeight: '700', textAlign: 'center' },
  cartaoDetalhes: { padding: 21 },
  bairro: { color: '#6d28d9', fontSize: 14, fontWeight: '800', marginBottom: 10 },
  distancia: { color: '#342744', fontSize: 14, fontWeight: '700', marginBottom: 10 },
  endereco: { color: '#777080', fontSize: 13, lineHeight: 20, marginBottom: 12 },
  dicaContainer: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12, marginTop: 12 },
  dicaLado: { flexDirection: 'row', alignItems: 'center', gap: 3 },
  dica: { color: '#9887ae', fontSize: 10, fontWeight: '800' },
  linhaComIcone: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  textoFlex: { flex: 1 },
  botaoConteudo: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 5 },
  tituloFavoritosLinha: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 14 },
  acoes: { flexDirection: 'row', gap: 12, marginTop: 20 },
  acao: { flex: 1, alignItems: 'center', justifyContent: 'center', borderRadius: 16, paddingVertical: 17 },
  descartar: { backgroundColor: '#fff', borderWidth: 1, borderColor: '#d9cce9' },
  gostei: { backgroundColor: '#6d28d9' },
  descartarTexto: { color: '#7e5c99', fontSize: 12, fontWeight: '900' },
  gosteiTexto: { color: '#fff', fontSize: 12, fontWeight: '900' },
  favoritos: { marginTop: 30 },
  favoritosTitulo: { color: '#241b35', fontSize: 19, fontWeight: '900', flexShrink: 1 },
  favoritoItem: { backgroundColor: '#fff', borderRadius: 14, padding: 15, marginBottom: 10, borderWidth: 1, borderColor: '#ece7f3' },
  favoritoNome: { fontSize: 15, fontWeight: '800', color: '#342744' },
  favoritoBairro: { fontSize: 12, color: '#777080', marginTop: 5 },
  aviso: { color: '#888', fontSize: 12, lineHeight: 18, marginTop: 8 },
  finalTitulo: { fontSize: 19, fontWeight: '900', color: '#6d28d9', textAlign: 'center' },
  botaoRecarregar: { backgroundColor: '#6d28d9', borderRadius: 14, paddingHorizontal: 20, paddingVertical: 14, marginTop: 20 },
  botaoRecarregarTexto: { fontSize: 12, fontWeight: '900', color: '#fff' },
});
