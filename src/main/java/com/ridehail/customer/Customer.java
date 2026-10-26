package com.ridehail.customer;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customers")
@Getter @Setter @NoArgsConstructor
public class Customer {
    @Id private UUID id = UUID.randomUUID();
    @Version private Long version;
    private String name;
    private String phone;
    /** Opaque token from the payment provider's client SDK; raw card data never reaches this server. */
    private String paymentMethodToken;
    /** The customer's id at the payment provider (e.g. Stripe cus_...), created at first card setup. */
    private String providerCustomerId;

    public Customer(String name, String phone) {
        this.name = name;
        this.phone = phone;
    }
}
