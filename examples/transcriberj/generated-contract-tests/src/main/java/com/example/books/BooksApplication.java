package com.example.books;

import com.example.books.application.BookStore;
import com.example.books.application.Catalogue;
import com.example.books.application.CatalogueService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.UUID;

/**
 * A catalogue of books: the service the {@code books} contract describes, written by hand.
 * It never imports a generated class.
 */
@SpringBootApplication
public class BooksApplication {

    /**
     * Starts the service.
     *
     * @param args the command line
     */
    public static void main(String[] args) {
        SpringApplication.run(BooksApplication.class, args);
    }

    /**
     * The catalogue, over the store, giving each new book a random identifier.
     *
     * @param store the store
     * @return the catalogue
     */
    @Bean
    Catalogue catalogue(BookStore store) {
        return new CatalogueService(store, UUID::randomUUID);
    }
}
