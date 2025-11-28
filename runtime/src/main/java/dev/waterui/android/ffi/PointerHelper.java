package dev.waterui.android.ffi;

import org.bytedeco.javacpp.Pointer;

/**
 * Helper class for working with JavaCPP Pointers and raw addresses.
 * 
 * JavaCPP's Pointer class doesn't have a public constructor that takes a long address.
 * This class provides utilities to convert between Long addresses and Pointer objects.
 */
public class PointerHelper {
    
    /**
     * Create a Pointer from a raw address.
     * Uses reflection to access the protected constructor.
     */
    public static Pointer fromAddress(long address) {
        if (address == 0) {
            return null;
        }
        return new AddressPointer(address);
    }
    
    /**
     * Get the raw address from a Pointer.
     */
    public static long toAddress(Pointer pointer) {
        return pointer != null ? pointer.address() : 0L;
    }
    
    /**
     * Internal Pointer subclass that allows setting the address.
     */
    private static class AddressPointer extends Pointer {
        AddressPointer(long address) {
            super((Pointer) null);
            this.address = address;
        }
    }
}

