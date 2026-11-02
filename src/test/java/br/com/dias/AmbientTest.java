package br.com.dias;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AmbientTest {

    @Test
    void deveSomarDoisNumeros() {
        int resultado = 2 + 3;

        assertEquals(5, resultado);
    }
}