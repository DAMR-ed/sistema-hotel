package hn.hotel.vpos;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.OleAuto;
import com.sun.jna.platform.win32.WTypes;
import com.sun.jna.win32.StdCallLibrary;

/**
 * PinPad real: llama a C:\EDPvPOS\EDPvPOSLink.dll, igual que el código original en VB.NET.
 *   Declare Function POSLinkAuthorize (BStr sInput, LPWStr sOutPut) As Boolean
 *   Declare Function POSLinkPrintReport (BStr sInput) As Boolean
 * IMPORTANTE: si el DLL es de 32 bits, este programa debe correr con Java de 32 bits (x86).
 */
public final class DllEdp implements Terminal {
    /** Funciones exportadas por el DLL (convención stdcall, la predeterminada de Declare en VB.NET). */
    public interface Edp extends StdCallLibrary {
        boolean POSLinkAuthorize(WTypes.BSTR entrada, char[] salida);
        boolean POSLinkPrintReport(WTypes.BSTR entrada);
    }

    private final Edp dll;

    public DllEdp(String ruta) {
        this.dll = Native.load(ruta, Edp.class);
    }

    @Override public synchronized Resultado autorizar(String entrada) {
        char[] buffer = new char[501];
        java.util.Arrays.fill(buffer, 0, 500, ' ');          // igual que sOutPut.PadRight(500)
        buffer[500] = 0;
        WTypes.BSTR b = OleAuto.INSTANCE.SysAllocString(entrada);
        try {
            boolean ok = dll.POSLinkAuthorize(b, buffer);
            int fin = 0;
            while (fin < buffer.length && buffer[fin] != 0) fin++;
            return new Resultado(ok, new String(buffer, 0, fin).trim());
        } finally {
            OleAuto.INSTANCE.SysFreeString(b);
        }
    }

    @Override public synchronized boolean imprimirReporte(String entrada) {
        WTypes.BSTR b = OleAuto.INSTANCE.SysAllocString(entrada);
        try { return dll.POSLinkPrintReport(b); }
        finally { OleAuto.INSTANCE.SysFreeString(b); }
    }

    @Override public String nombre() { return "DLL EDPvPOSLink"; }
}
