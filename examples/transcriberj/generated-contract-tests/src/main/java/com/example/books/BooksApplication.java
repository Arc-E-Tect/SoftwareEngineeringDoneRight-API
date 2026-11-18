package com.example.books;

import com.example.books.application.BookStore;
import com.example.books.application.Catalogue;
import com.example.books.application.CatalogueService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
     * The catalogue, over the store, giving each new book a random identifier, and adding a
     * book added later on a thread of its own.
     *
     * @param store      the store
     * @param background where a book added later is added
     * @return the catalogue
     */
    @Bean
    Catalogue catalogue(BookStore store, Executor background) {
        return new CatalogueService(store, UUID::randomUUID, background);
    }

    /**
     * Where a book added later is added: a virtual thread per book, which the application
     * waits for when it stops.
     *
     * @return the executor
     */
    @Bean(destroyMethod = "close")
    ExecutorService bookAdditions() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
