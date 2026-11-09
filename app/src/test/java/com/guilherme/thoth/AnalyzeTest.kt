package com.guilherme.thoth

import com.guilherme.thoth.core.Analyze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fictional matrículas (invented names), covering the cases the analysis must get right. */
private val RURAL = """
REGISTRO DE IMÓVEIS
Matrícula N.º 100 PROTOC. 2000 FLS.10
Data 10.01.80
PROPRIETÁRIO: João Pedro Alves, casado, e s/esposa Maria Clara Alves, lavradores. IMÓVEL: Um terreno rural com a área de 10,00 ha (dez hectares), situado no Córrego Fundo, neste município.
R.01 - 100 Data: 05.03.85 TRANSMITENTE: João Pedro Alves e s/m Maria Clara Alves, brasileiros. ADQUIRENTE: Carlos Eduardo Lima, solteiro. TÍTULO: Escritura de compra e venda. OBJETO: Uma parte de 4,00 ha do imóvel desta matrícula.
R.02 - 100 Data: 06.03.85 DEVEDOR: Carlos Eduardo Lima. CREDOR: Banco Rural S.A. TÍTULO: Cédula rural hipotecária, em garantia de empréstimo.
AV.03 - 100 Data: 10.10.90 Desmembramento: foi desmembrada desta matrícula a área de 1,00 ha pertencente a Carlos Eduardo Lima, transportada para a matrícula n.º 250.
R.04 - 100 Data: 12.12.95 TRANSMITENTE: Maria Clara Alves, viúva. ADQUIRENTE: Ana Paula Souza, solteira. TÍTULO: Escritura de compra e venda.
AV.05 - 100 Data: 01.02.96 Fica cancelada a hipoteca do R.02, por quitação.
"""

private val URBANO = """
REGISTRO DE IMÓVEIS
Matricula N.º 321 PROTOC. 3134 FLS.51        N.º Anterior L.02 Fls.979 nº
Data 26.04.83                                 124 R.02
PROPRIETARIO:Rui Barbosa Lima, casado, s/m.Helena Costa Lima.IMÓVEL:Urbano-Rua das Flores-Divino-MG.SÓMEN-
TE:Lote terreno e uma casa de residencia em mau estado de conservação, medindo lote e casa frente por,digo,llmfrente por 28m fun-/
dos, situada a rua das Flores, nesta cidade.
REG.01 MAT. 321PROTOC. 3134 FLS.51
DATA:26.04.83
TRANSMITENTE:Rui Barbosa Lima, casado, e s/esposa Helena Costa Lima, do lar ambos brasileiros. ADQUIRENTE:Pedro Henrique Souto, casado, agricultor. TITULO:Escritura Pública de Compra e Venda. SÓMENTE:Lote terreno e uma casa de residencia.
"""

private val HERANCA = """
Matrícula N.º 77 Data 02.02.70
PROPRIETÁRIO: José Ramos Neto, viúvo. IMÓVEL: Uma gleba de terras com a área de 2 alqueires, no lugar Boa Vista.
R.1-77 Data: 03.03.80 TRANSMITENTE: Espólio de José Ramos Neto. ADQUIRENTES: Luiz Ramos e Paulo Ramos, solteiros. TÍTULO: Formal de partilha.
R.2-77 Data: 04.04.90 TRANSMITENTE: Paulo Ramos. ADQUIRENTE: Marta Dias Ferreira, casada. TÍTULO: Compra e venda. OBJETO: a metade ideal do imóvel.
AV.3-77 Data: 05.05.95 Em virtude de desapropriação para a estrada, fica com a área remanescente de 8,00 ha.
R.4-77 Data: 06.06.99 DEVEDOR: Marta Dias Ferreira. CREDOR: Banco X. TÍTULO: Hipoteca.
"""

class AnalyzeTest {
    private fun owners(r: Analyze.Result) = r.holders.associate { (h, _) -> h.names.first() to Math.round(h.amount) }

    @Test fun partialSaleSplitAndWidow() {
        val r = Analyze.analyze(RURAL)
        assertEquals(100000.0, r.totalM2!!, 0.1)
        assertEquals(10000.0, r.leftM2, 0.1)
        assertEquals(90000.0, r.remainingM2!!, 0.1)
        assertEquals(mapOf("Carlos Eduardo Lima" to 30000L, "Ana Paula Souza" to 60000L), owners(r))
        assertEquals("Ana Paula Souza", r.latest!!.names.first())
        assertTrue("canceled mortgage must not be listed", r.liens.isEmpty())
    }

    @Test fun typewriterFrontTimesDepth() {
        val r = Analyze.analyze(URBANO)
        assertEquals(308.0, r.totalM2!!, 0.1)
        assertEquals(mapOf("Pedro Henrique Souto" to 308L), owners(r))
        assertEquals(listOf("Rui Barbosa Lima", "Helena Costa Lima"), r.acts.first().to)
    }

    @Test fun heirsHalfOfWholeAndRemainder() {
        val r = Analyze.analyze(HERANCA)
        assertEquals(96800.0, r.totalM2!!, 0.1)
        assertEquals(80000.0, r.remainingM2!!, 0.1)
        assertEquals(mapOf("Luiz Ramos" to 40000L, "Marta Dias Ferreira" to 40000L), owners(r))
        assertEquals(1, r.liens.size)
    }

    @Test fun areaFormats() {
        assertEquals(24200.0, Analyze.areas("com 2ha 42a 00ca de terras").first().m2, 0.01)
        assertEquals(1250.5, Analyze.areas("área de 1.250,50 m²").first().m2, 0.01)
        assertEquals(0.5, Analyze.fraction("a metade ideal")!!, 1e-9)
    }
}
