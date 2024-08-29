package ai.vespa.schemals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensDelta;
import org.eclipse.lsp4j.SemanticTokensDeltaParams;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.TextDocumentService;

import ai.vespa.schemals.common.ClientLogger;
import ai.vespa.schemals.context.EventContextCreator;
import ai.vespa.schemals.context.EventDocumentContext;
import ai.vespa.schemals.index.SchemaIndex;
import ai.vespa.schemals.lsp.codeaction.SchemaCodeAction;
import ai.vespa.schemals.lsp.completion.SchemaCompletion;
import ai.vespa.schemals.lsp.definition.SchemaDefinition;
import ai.vespa.schemals.lsp.documentsymbols.SchemaDocumentSymbols;
import ai.vespa.schemals.lsp.hover.SchemaHover;
import ai.vespa.schemals.lsp.references.SchemaReferences;
import ai.vespa.schemals.lsp.rename.SchemaPrepareRename;
import ai.vespa.schemals.lsp.rename.SchemaRename;
import ai.vespa.schemals.lsp.semantictokens.SchemaSemanticTokens;
import ai.vespa.schemals.schemadocument.SchemaDocumentScheduler;

/**
 * SchemaTextDocumentService handles incomming requests from the client.
 */
public class SchemaTextDocumentService implements TextDocumentService {

    private ClientLogger logger;
    private EventContextCreator eventContextCreator;
    private SchemaMessageHandler schemaMessageHandler;

    public SchemaTextDocumentService(ClientLogger logger, SchemaDocumentScheduler schemaDocumentScheduler, SchemaIndex schemaIndex, SchemaMessageHandler schemaMessageHandler) {
        this.logger = logger;
        this.schemaMessageHandler = schemaMessageHandler;
        eventContextCreator = new EventContextCreator(schemaDocumentScheduler, schemaIndex, schemaMessageHandler);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams completionParams) {
        // Provide completion item.
        return CompletableFutures.computeAsync((cancelChecker) -> {
            try {

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                PrintStream errorLogger = new PrintStream(outputStream);
                Either<List<CompletionItem>, CompletionList> result =
                    Either.forLeft(SchemaCompletion.getCompletionItems(eventContextCreator.createContext(completionParams), errorLogger));

                if (outputStream.size() > 0) {
                    schemaMessageHandler.logMessage(MessageType.Error, 
                        "Completion failed with errors: " + outputStream.toString() 
                    );
                }
                return result;
            } catch(CancellationException ignore) {
                // Ignore
            }

            // Return the list of completion items.
            return Either.forLeft(new ArrayList<>());
        });
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem completionItem) {
        return null;
    }


    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            try {
                return SchemaCodeAction.provideActions(eventContextCreator.createContext(params));
            } catch(Exception e) {
                logger.error("Error during code action handling: " + e.getMessage());
            }

            return new ArrayList<>();
        });
    }

    @Override
    public CompletableFuture<CodeAction> resolveCodeAction(CodeAction unresolved) {
        return CompletableFutures.computeAsync((cancelChecker) -> {

            return unresolved;
        });
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            return SchemaReferences.getReferences(eventContextCreator.createContext(params));
        });
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
            DocumentSymbolParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            try {
                EventDocumentContext context = eventContextCreator.createContext(params);
                return SchemaDocumentSymbols.documentSymbols(context);
            } catch(Exception e) {
                logger.error("Error during document symbol handling: " + e.getMessage());
            }
            return List.of();
        });
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams codeLensParams) {
        return null;
    }

    @Override
    public CompletableFuture<CodeLens> resolveCodeLens(CodeLens codeLens) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams documentFormattingParams) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams documentRangeFormattingParams) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams documentOnTypeFormattingParams) {
        return null;
    }

    @Override
    public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(PrepareRenameParams params) {

        return CompletableFutures.computeAsync((cancelChecker) -> {
            return SchemaPrepareRename.prepareRename(eventContextCreator.createContext(params));
        });
	}

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {

        return CompletableFutures.computeAsync((cancelChecker) -> {
            return SchemaRename.rename(eventContextCreator.createContext(params), params.getNewName());
        });
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem document = params.getTextDocument();

        SchemaDocumentScheduler scheduler = eventContextCreator.scheduler;

        scheduler.openDocument(document);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        var document = params.getTextDocument();
        SchemaDocumentScheduler scheduler = eventContextCreator.scheduler;

        var contentChanges = params.getContentChanges();
        for (int i = 0; i < contentChanges.size(); i++) {
            try {
                scheduler.updateFile(document.getUri(), contentChanges.get(i).getText());
            } catch(Exception e) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                PrintStream logger = new PrintStream(outputStream);

                e.printStackTrace(logger);

                schemaMessageHandler.logMessage(MessageType.Error, 
                    "Updating file " + document.getUri() + " failed with error: " + outputStream.toString() 
                );
            }
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        TextDocumentIdentifier documentIdentifier = params.getTextDocument();
        SchemaDocumentScheduler scheduler = eventContextCreator.scheduler;

        scheduler.closeDocument(documentIdentifier.getUri());
    }

    @Override
    public void didSave(DidSaveTextDocumentParams didSaveTextDocumentParams) {

    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(DocumentHighlightParams params) {

        return CompletableFutures.computeAsync((cancelChecker) -> {
            return new ArrayList<DocumentHighlight>();
        });
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
            try {

                var result = SchemaSemanticTokens.getSemanticTokens(eventContextCreator.createContext(params));
                return result;
                 
            } catch (CancellationException ignore) {
                // Ignore cancellation exception
            } catch (Exception e) {
                logger.error(e.getMessage());;
            }

            return new SemanticTokens(new ArrayList<>());
        });
    }

    public CompletableFuture<Either<SemanticTokens, SemanticTokensDelta>> semanticTokensFullDelta(SemanticTokensDeltaParams params) {
        return null;
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {

            try {

                return SchemaHover.getHover(eventContextCreator.createContext(params));

            } catch (CancellationException ignore) {
                // Ignore
            } catch (Exception e) {
                logger.error(e.getMessage());
            }

            return null; 
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFutures.computeAsync((cancelChecker) -> {
    
            try {
    
                return Either.forLeft(SchemaDefinition.getDefinition(eventContextCreator.createContext(params)));
    
            } catch (CancellationException ignore) {
                // Ignore
            } catch (Exception e) {
                logger.error(e);
            }
    
            return Either.forLeft(new ArrayList<Location>());
        });
    }
}
